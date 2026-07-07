package com.fraudshield.service;

import com.fraudshield.dto.GraphRiskScoreDTO;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 图风险传播 — 在用户–IP二部图上做迭代式风险分数传播（幂迭代同族算法）
 * Graph risk propagation: iterative score propagation on the user–IP bipartite
 * graph — the same algorithm family as the power method / PageRank.
 *
 * <p>动机：linked-accounts只能看两跳（A和B共享一个IP）。真实欺诈团伙是多跳结构：
 * A→IP1→B→IP2→C —— C和已确认的欺诈者A没有任何直接共享，但通过图传播，
 * A的确认标注会衰减着流到C。
 * Motivation: the linked-accounts view only sees two hops (A and B share an IP).
 * Real fraud rings are multi-hop: A→IP1→B→IP2→C — C shares nothing directly with
 * confirmed fraudster A, yet propagation lets A's label flow (attenuated) to C.
 *
 * <p>算法 (the algorithm):
 * <ol>
 *   <li>节点 = 用户 ∪ IP；边 = "该用户从该IP下过单"。</li>
 *   <li>种子向量：人工确认欺诈的用户 = 1.0，其他 = 0。种子来自审核标注 ——
 *       又一个review workflow的下游价值。</li>
 *   <li>迭代：score(v) = (1−d)·seed(v) + d·mean(neighbor scores)，阻尼d=0.5。
 *       与PageRank的区别：用mean而不是sum/degree转移，保证分数始终落在[0,1]，
 *       可直接解释为"风险浓度"。</li>
 *   <li>收敛判据：相邻两轮分数向量之差的欧几里得范数 &lt; ε，或达到最大轮数 ——
 *       幂迭代的标准停机条件。</li>
 * </ol>
 *
 * <p>Seeds come from review labels (CONFIRMED_FRAUD users = 1.0). Iteration:
 * score(v) = (1−d)·seed(v) + d·mean(neighbors), damping d = 0.5 — using the mean
 * rather than PageRank's degree-normalized sum keeps every score in [0,1], directly
 * readable as "risk concentration". Convergence: Euclidean norm of the score-vector
 * delta &lt; ε, or a max-iteration cap — the standard power-iteration stopping rule.
 *
 * <p>规模注记：全图在内存中重建，对demo规模（几千事件）毫秒级。真实规模下这一步
 * 变成离线批计算（Spark GraphX / Neo4j GDS），在线只查预计算好的分数。
 * Scale note: the graph is rebuilt in memory per call — milliseconds at demo scale.
 * At production scale this becomes an offline batch job with precomputed scores.
 */
@Service
public class GraphRiskService {

    private static final Logger log = LoggerFactory.getLogger(GraphRiskService.class);

    private static final double DAMPING = 0.5;
    private static final double EPSILON = 1e-6;
    private static final int MAX_ITERATIONS = 50;

    // 节点前缀防止用户名与IP字符串撞车 / prefixes keep user and IP node ids disjoint
    private static final String USER_PREFIX = "u:";
    private static final String IP_PREFIX = "ip:";

    private final RiskEventRepository repository;

    public GraphRiskService(RiskEventRepository repository) {
        this.repository = repository;
    }

    /**
     * 计算全图传播分数，返回用户节点的非零分数（降序）
     * Propagate over the full graph; return non-zero user scores, highest first.
     */
    public List<GraphRiskScoreDTO> computeUserRiskScores() {
        List<RiskEvent> events = repository.findAll();

        // ── 建图 / build adjacency ────────────────────────────────────────────
        Map<String, Set<String>> adjacency = new HashMap<>();
        Map<String, Double> seed = new HashMap<>();

        for (RiskEvent e : events) {
            if (e.getUserId() == null || e.getIpAddress() == null) {
                continue;
            }
            String userNode = USER_PREFIX + e.getUserId();
            String ipNode = IP_PREFIX + e.getIpAddress();
            adjacency.computeIfAbsent(userNode, k -> new HashSet<>()).add(ipNode);
            adjacency.computeIfAbsent(ipNode, k -> new HashSet<>()).add(userNode);

            if ("CONFIRMED_FRAUD".equals(e.getReviewStatus())) {
                seed.put(userNode, 1.0);
            }
        }

        if (seed.isEmpty()) {
            // 没有确认标注就没有传播源 —— 返回空而不是全零列表
            // No confirmed labels = nothing to propagate; empty beats an all-zeros list
            return List.of();
        }

        // ── 幂迭代式传播 / power-iteration-style propagation ──────────────────
        Map<String, Double> scores = new HashMap<>(seed);
        int iterations = 0;
        double delta = Double.MAX_VALUE;

        while (iterations < MAX_ITERATIONS && delta > EPSILON) {
            Map<String, Double> next = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : adjacency.entrySet()) {
                String node = entry.getKey();
                Set<String> neighbors = entry.getValue();
                double neighborMean = neighbors.stream()
                        .mapToDouble(n -> scores.getOrDefault(n, 0.0))
                        .average()
                        .orElse(0.0);
                double value = (1 - DAMPING) * seed.getOrDefault(node, 0.0)
                        + DAMPING * neighborMean;
                if (value > 0) {
                    next.put(node, value);
                }
            }
            delta = euclideanDelta(scores, next, adjacency.keySet());
            scores.clear();
            scores.putAll(next);
            iterations++;
        }
        log.debug("Graph risk propagation converged after {} iterations (delta={})",
                iterations, delta);

        // ── 只返回用户节点 / project back to user nodes only ──────────────────
        return scores.entrySet().stream()
                .filter(en -> en.getKey().startsWith(USER_PREFIX) && en.getValue() > EPSILON)
                .map(en -> GraphRiskScoreDTO.builder()
                        .userId(en.getKey().substring(USER_PREFIX.length()))
                        .score(Math.round(en.getValue() * 1000.0) / 1000.0)
                        .confirmedFraudster(seed.containsKey(en.getKey()))
                        .build())
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }

    /** 单个用户的传播分数（无分数时0.0）/ one user's propagated score, 0.0 when absent. */
    public double getUserRiskScore(String userId) {
        return computeUserRiskScores().stream()
                .filter(s -> s.getUserId().equals(userId))
                .mapToDouble(GraphRiskScoreDTO::getScore)
                .findFirst()
                .orElse(0.0);
    }

    // 收敛判据：‖scoreₖ₊₁ − scoreₖ‖₂ — 幂迭代的标准停机条件
    // Convergence: Euclidean norm of the score-vector delta, the standard
    // power-iteration stopping rule.
    private double euclideanDelta(Map<String, Double> prev, Map<String, Double> next,
                                  Set<String> nodes) {
        double sumSquares = 0.0;
        for (String node : nodes) {
            double d = next.getOrDefault(node, 0.0) - prev.getOrDefault(node, 0.0);
            sumSquares += d * d;
        }
        return Math.sqrt(sumSquares);
    }

    /** 返回按分数降序的前N个高风险用户 / top-N riskiest users by propagated score. */
    public List<GraphRiskScoreDTO> topRiskyUsers(int limit) {
        List<GraphRiskScoreDTO> all = computeUserRiskScores();
        return all.subList(0, Math.min(limit, all.size()));
    }
}
