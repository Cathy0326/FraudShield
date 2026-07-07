package com.fraudshield.service;

import com.fraudshield.dto.GraphRiskScoreDTO;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GraphRiskServiceTest {

    @Mock RiskEventRepository repository;

    private GraphRiskService service;

    @BeforeEach
    void setUp() {
        service = new GraphRiskService(repository);
    }

    private RiskEvent event(String userId, String ip, String reviewStatus) {
        return RiskEvent.builder()
                .orderId("ORD-" + userId + "-" + ip)
                .userId(userId)
                .ipAddress(ip)
                .riskLevel("HIGH")
                .reviewStatus(reviewStatus)
                .build();
    }

    @Test
    void multiHopRing_scoresDecayWithDistance() {
        // 团伙链：FRAUDSTER —IP1— MULE —IP2— RECRUIT；CLEAN完全隔离
        // Ring chain: FRAUDSTER —IP1— MULE —IP2— RECRUIT; CLEAN is fully isolated.
        // RECRUIT shares NOTHING directly with FRAUDSTER — only propagation finds them.
        when(repository.findAll()).thenReturn(List.of(
                event("FRAUDSTER", "1.1.1.1", "CONFIRMED_FRAUD"),
                event("MULE",      "1.1.1.1", null),
                event("MULE",      "2.2.2.2", null),
                event("RECRUIT",   "2.2.2.2", null),
                event("CLEAN",     "9.9.9.9", null)
        ));

        Map<String, Double> scores = service.computeUserRiskScores().stream()
                .collect(Collectors.toMap(GraphRiskScoreDTO::getUserId, GraphRiskScoreDTO::getScore));

        // 分数随图距离单调衰减 / scores decay monotonically with graph distance
        assertThat(scores.get("FRAUDSTER")).isGreaterThan(scores.get("MULE"));
        assertThat(scores.get("MULE")).isGreaterThan(scores.get("RECRUIT"));
        // 多跳节点确实拿到了非零分 —— 两跳的linked-accounts看不到这个
        // The multi-hop node got a non-zero score — invisible to two-hop linked accounts
        assertThat(scores.get("RECRUIT")).isGreaterThan(0.0);
        // 无关联的干净用户不得分 / the isolated clean user gets nothing
        assertThat(scores).doesNotContainKey("CLEAN");
    }

    @Test
    void confirmedFraudster_isFlaggedAsSeed() {
        when(repository.findAll()).thenReturn(List.of(
                event("FRAUDSTER", "1.1.1.1", "CONFIRMED_FRAUD"),
                event("NEIGHBOR",  "1.1.1.1", null)
        ));

        List<GraphRiskScoreDTO> scores = service.computeUserRiskScores();

        GraphRiskScoreDTO fraudster = scores.stream()
                .filter(s -> s.getUserId().equals("FRAUDSTER")).findFirst().orElseThrow();
        GraphRiskScoreDTO neighbor = scores.stream()
                .filter(s -> s.getUserId().equals("NEIGHBOR")).findFirst().orElseThrow();
        assertThat(fraudster.isConfirmedFraudster()).isTrue();
        assertThat(neighbor.isConfirmedFraudster()).isFalse();
    }

    @Test
    void noConfirmedFraudLabels_returnsEmpty() {
        // 没有传播源时返回空列表，而不是一堆0分
        // No seeds = nothing to propagate; empty list, not a pile of zeros
        when(repository.findAll()).thenReturn(List.of(
                event("A", "1.1.1.1", null),
                event("B", "1.1.1.1", "FALSE_POSITIVE")
        ));

        assertThat(service.computeUserRiskScores()).isEmpty();
    }

    @Test
    void scoresStayWithinZeroToOne() {
        // mean传播 + 阻尼保证分数不发散 —— 这是选mean而不是sum的原因
        // Mean-based propagation with damping keeps scores bounded — why mean, not sum
        when(repository.findAll()).thenReturn(List.of(
                event("F1", "1.1.1.1", "CONFIRMED_FRAUD"),
                event("F2", "1.1.1.1", "CONFIRMED_FRAUD"),
                event("F3", "1.1.1.1", "CONFIRMED_FRAUD"),
                event("HUB", "1.1.1.1", null)
        ));

        List<GraphRiskScoreDTO> scores = service.computeUserRiskScores();

        assertThat(scores).allSatisfy(s ->
                assertThat(s.getScore()).isBetween(0.0, 1.0));
    }

    @Test
    void getUserRiskScore_unknownUser_isZero() {
        when(repository.findAll()).thenReturn(List.of(
                event("FRAUDSTER", "1.1.1.1", "CONFIRMED_FRAUD")
        ));

        assertThat(service.getUserRiskScore("NOBODY")).isZero();
    }

    @Test
    void eventsWithNullUserOrIp_areSkippedWithoutCrashing() {
        when(repository.findAll()).thenReturn(List.of(
                event("FRAUDSTER", "1.1.1.1", "CONFIRMED_FRAUD"),
                event(null, "1.1.1.1", null),
                event("GHOST", null, null)
        ));

        List<GraphRiskScoreDTO> scores = service.computeUserRiskScores();

        assertThat(scores).extracting(GraphRiskScoreDTO::getUserId)
                .containsExactly("FRAUDSTER");
    }
}
