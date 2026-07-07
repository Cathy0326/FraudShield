package com.fraudshield.service;

import com.fraudshield.dto.RulePrecisionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 规则权重 — 由审核标注的精度推导，不再拍脑袋
 * Rule weights derived from measured precision, not guesses.
 *
 * <p>权重规则 (weighting policy):
 * <ul>
 *   <li>标注数不足{@value #MIN_LABELS}条 → 权重1.0（未测量 ≠ 不可信；
 *       维持规则原有影响力，避免冷启动时全体降权）。</li>
 *   <li>标注充足 → weight = precision/100，下限{@value #MIN_WEIGHT}
 *       （再差的规则也保留少量影响力，它命中的事实本身仍是弱信号）。</li>
 * </ul>
 *
 * <ul>
 *   <li>Fewer than {@value #MIN_LABELS} reviewed hits → weight 1.0. Unmeasured is not
 *       the same as untrustworthy; keeping full weight avoids demoting every rule
 *       during cold start.</li>
 *   <li>Enough labels → weight = precision/100, floored at {@value #MIN_WEIGHT} —
 *       even a noisy rule's hit remains a weak signal worth something.</li>
 * </ul>
 *
 * <p>每60秒后台刷新一次缓存 —— 权重进入每一笔订单的实时评分路径，绝不能在
 * 热路径上做全表精度计算。
 * Refreshed every 60s in the background: weights sit on the per-order hot path,
 * so the full-table precision computation must never run inline.
 */
@Service
public class RuleWeightService {

    private static final Logger log = LoggerFactory.getLogger(RuleWeightService.class);
    private static final int MIN_LABELS = 3;
    private static final double MIN_WEIGHT = 0.2;
    private static final double DEFAULT_WEIGHT = 1.0;

    private final RiskEventService riskEventService;

    // volatile + 整体替换：读侧无锁 / volatile swap keeps the read side lock-free
    private volatile Map<String, Double> weights = Map.of();

    public RuleWeightService(RiskEventService riskEventService) {
        this.riskEventService = riskEventService;
    }

    /** 该规则的当前权重 / current weight for a rule (1.0 when unmeasured). */
    public double weightFor(String ruleName) {
        return weights.getOrDefault(ruleName, DEFAULT_WEIGHT);
    }

    @Scheduled(fixedRate = 60_000)
    public void refresh() {
        try {
            Map<String, Double> next = new HashMap<>();
            for (RulePrecisionDTO stat : riskEventService.getRulePrecision()) {
                if (stat.getReviewedHits() >= MIN_LABELS && stat.getPrecision() != null) {
                    next.put(stat.getRule(),
                            Math.max(MIN_WEIGHT, stat.getPrecision() / 100.0));
                }
            }
            weights = next;
            if (!next.isEmpty()) {
                log.debug("Refreshed rule weights: {}", next);
            }
        } catch (Exception e) {
            // 刷新失败沿用旧权重 —— 评分路径的可用性优先于权重新鲜度
            // Keep serving the old weights on failure; scoring availability
            // outranks weight freshness.
            log.warn("Rule weight refresh failed, keeping previous weights: {}", e.getMessage());
        }
    }
}
