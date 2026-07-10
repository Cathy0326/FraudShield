package com.fraudshield.service;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.dto.RiskEventDTO;
import com.fraudshield.exception.ResourceNotFoundException;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskEventServiceTest {

    @Mock RiskEventRepository repository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock AuditChainService auditChain;

    private RiskEventService service;

    private RiskEvent event1, event2, event3;

    @BeforeEach
    void setUp() {
        service = new RiskEventService(repository, redisTemplate, auditChain);

        LocalDateTime now = LocalDateTime.now();
        event1 = buildEvent(1L, "ORD-001", "HIGH",   1.0,  "BlacklistRule",         now);
        event2 = buildEvent(2L, "ORD-002", "MEDIUM",  0.6,  "HighAmountNewUserRule", now.minusHours(1));
        event3 = buildEvent(3L, "ORD-003", "HIGH",   0.9,  "FrequentIpRule",        now.minusHours(2));
    }

    private RiskEvent buildEvent(Long id, String orderId, String level,
                                  double score, String rules, LocalDateTime at) {
        return RiskEvent.builder()
                .id(id).orderId(orderId).userId("USER-1").ipAddress("1.2.3.4")
                .amount(100.0).riskLevel(level).riskScore(score)
                .triggeredRules(rules).explanation("test").detectedAt(at)
                .build();
    }

    @Test
    void getRecentEvents_returnsConvertedDTOs() {
        when(repository.findTop10ByOrderByDetectedAtDesc()).thenReturn(List.of(event1, event2, event3));

        List<RiskEventDTO> result = service.getRecentEvents(10);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getOrderId()).isEqualTo("ORD-001");
        // Verify CSV → List conversion happened
        assertThat(result.get(0).getTriggeredRules()).isInstanceOf(List.class);
        assertThat(result.get(0).getTriggeredRules()).containsExactly("BlacklistRule");
    }

    @Test
    void getEventsByRiskLevel_filtersCorrectly() {
        when(repository.findByRiskLevelOrderByDetectedAtDesc("HIGH"))
                .thenReturn(List.of(event1, event3));

        List<RiskEventDTO> result = service.getEventsByRiskLevel("HIGH");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> "HIGH".equals(e.getRiskLevel()));
    }

    @Test
    void getDashboardStats_aggregatesCorrectly() {
        when(repository.countByRiskLevel("HIGH")).thenReturn(2L);
        when(repository.countByRiskLevel("MEDIUM")).thenReturn(1L);
        when(repository.countByRiskLevel("LOW")).thenReturn(0L);
        when(repository.findAll()).thenReturn(List.of(event1, event2, event3));
        when(repository.findByDetectedAtBetween(any(), any())).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("counter:normal_orders")).thenReturn("5");

        DashboardStatsDTO stats = service.getDashboardStats();

        assertThat(stats.getTotalOrders()).isEqualTo(3L);   // 2 HIGH + 1 MEDIUM
        assertThat(stats.getNormalCount()).isEqualTo(5L);
        assertThat(stats.getRuleHitCounts()).containsEntry("BlacklistRule", 1L);
        assertThat(stats.getRuleHitCounts()).containsEntry("HighAmountNewUserRule", 1L);
        assertThat(stats.getRuleHitCounts()).containsEntry("FrequentIpRule", 1L);
    }

    @Test
    void getEventByOrderId_found_returnsDTO() {
        when(repository.findByOrderId("ORD-001")).thenReturn(Optional.of(event1));

        RiskEventDTO dto = service.getEventByOrderId("ORD-001");

        assertThat(dto.getOrderId()).isEqualTo("ORD-001");
    }

    @Test
    void getEventByOrderId_notFound_throwsResourceNotFoundException() {
        when(repository.findByOrderId("NONEXISTENT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEventByOrderId("NONEXISTENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NONEXISTENT");
    }

    @Test
    void incrementNormalCounter_callsRedisIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.incrementNormalCounter();

        verify(valueOps).increment("counter:normal_orders");
    }

    // ── 财务影响 / financial impact ───────────────────────────────────────────

    @Test
    void financialImpact_sumsAmountsByOutcome_ratioGuardsDivZero() {
        event1.setReviewStatus("CONFIRMED_FRAUD");  // $100 intercepted
        event2.setReviewStatus("FALSE_POSITIVE");   // $100 wrongly blocked
        event3.setReviewStatus("PENDING_REVIEW");   // pending - excluded
        when(repository.findAll()).thenReturn(List.of(event1, event2, event3));

        var impact = service.getFinancialImpact();

        assertThat(impact.getInterceptedAmount()).isEqualTo(100.0);
        assertThat(impact.getFalsePositiveAmount()).isEqualTo(100.0);
        assertThat(impact.getInterceptToFalseKillRatio()).isEqualTo(1.0);

        // 误杀为0时比率为null（前端显示∞），绝不除零
        // ratio is null (frontend shows infinity) when nothing was wrongly blocked
        event2.setReviewStatus("APPROVED");
        var impact2 = service.getFinancialImpact();
        assertThat(impact2.getInterceptToFalseKillRatio()).isNull();
        assertThat(impact2.getApprovedAmount()).isEqualTo(100.0);
    }

    // ── 基线与周同比 / trend baseline & week-over-week ────────────────────────

    @Test
    void hourlyTrend_baselineIsSameHourMeanOverPrior7Days() {
        // 过去7天中的3天，在同一钟点各有1单HIGH → 该钟点基线均值 = 3/7
        // 3 of the prior 7 days have one HIGH at the same hour -> baseline mean 3/7
        LocalDateTime slot = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        List<RiskEvent> window = List.of(
                buildEvent(10L, "ORD-D1", "HIGH", 1.0, "R", slot.minusDays(1).plusMinutes(5)),
                buildEvent(11L, "ORD-D2", "HIGH", 1.0, "R", slot.minusDays(2).plusMinutes(10)),
                buildEvent(12L, "ORD-D3", "HIGH", 1.0, "R", slot.minusDays(3).plusMinutes(20)));
        when(repository.findByDetectedAtBetween(any(), any())).thenReturn(window);
        when(repository.countByRiskLevel(any())).thenReturn(0L);
        when(repository.findAll()).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("0");

        DashboardStatsDTO stats = service.getDashboardStats();

        // 当前小时是趋势的最后一个桶 / the current hour is the trend's last bucket
        var current = stats.getHourlyTrend().get(23);
        assertThat(current.getBaselineRisk()).isEqualTo(0.43);   // 3/7 rounded
        assertThat(current.getBaselineSigma()).isGreaterThan(0.0);
    }

    @Test
    void weekOverWeekDeltas_compareLast7dAgainstPrior7d() {
        LocalDateTime now = LocalDateTime.now();
        List<RiskEvent> window = List.of(
                // 近7天：2 HIGH / this week: two HIGH
                buildEvent(20L, "ORD-W1", "HIGH", 1.0, "R", now.minusDays(1)),
                buildEvent(21L, "ORD-W2", "HIGH", 1.0, "R", now.minusDays(2)),
                // 上个7天：1 HIGH, 1 MEDIUM / prior week: one of each
                buildEvent(22L, "ORD-P1", "HIGH", 1.0, "R", now.minusDays(9)),
                buildEvent(23L, "ORD-P2", "MEDIUM", 0.6, "R", now.minusDays(10)));
        when(repository.findByDetectedAtBetween(any(), any())).thenReturn(window);
        when(repository.countByRiskLevel(any())).thenReturn(0L);
        when(repository.findAll()).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("0");

        DashboardStatsDTO stats = service.getDashboardStats();

        assertThat(stats.getHighRiskWowDelta()).isEqualTo(1L);    // 2 - 1
        assertThat(stats.getMediumRiskWowDelta()).isEqualTo(-1L); // 0 - 1
    }

    // ── 审核工作流 / Review workflow ──────────────────────────────────────────

    @Test
    void reviewEvent_pendingEvent_transitionsAndRecordsReviewer() {
        when(repository.findByOrderId("ORD-001")).thenReturn(Optional.of(event1));
        when(repository.save(any(RiskEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        RiskEventDTO dto = service.reviewEvent("ORD-001", "CONFIRMED_FRAUD", "admin", "chargeback reported");

        assertThat(dto.getReviewStatus()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(dto.getReviewedBy()).isEqualTo("admin");
        assertThat(dto.getReviewedAt()).isNotNull();
        assertThat(dto.getReviewNotes()).isEqualTo("chargeback reported");
        // 每个决定都追加到防篡改审计链 / every decision lands on the audit chain
        verify(auditChain).append("ORD-001", "CONFIRMED_FRAUD", "admin");
    }

    @Test
    void reviewEvent_legacyNullStatus_isTreatedAsPending() {
        // 该列加入前写入的行status为NULL，必须仍可审核
        // Rows written before the column existed must still be reviewable
        event1.setReviewStatus(null);
        when(repository.findByOrderId("ORD-001")).thenReturn(Optional.of(event1));
        when(repository.save(any(RiskEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        RiskEventDTO dto = service.reviewEvent("ORD-001", "APPROVED", "operator", null);

        assertThat(dto.getReviewStatus()).isEqualTo("APPROVED");
    }

    @Test
    void reviewEvent_alreadyReviewed_throwsConflict() {
        // 终态不可变：重复审核会污染规则精度统计的标注数据
        // Terminal states are immutable — re-reviewing corrupts label data
        event1.setReviewStatus("FALSE_POSITIVE");
        event1.setReviewedBy("operator");
        when(repository.findByOrderId("ORD-001")).thenReturn(Optional.of(event1));

        assertThatThrownBy(() -> service.reviewEvent("ORD-001", "CONFIRMED_FRAUD", "admin", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already reviewed");
        verify(repository, never()).save(any());
    }

    @Test
    void reviewEvent_invalidDecision_throwsBadRequest() {
        assertThatThrownBy(() -> service.reviewEvent("ORD-001", "MAYBE_FRAUD", "admin", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MAYBE_FRAUD");
        verify(repository, never()).findByOrderId(any());
    }

    @Test
    void reviewEvent_unknownOrder_throwsNotFound() {
        when(repository.findByOrderId("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reviewEvent("NOPE", "APPROVED", "admin", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getReviewQueue_returnsPendingEvents() {
        when(repository.findPendingReview()).thenReturn(List.of(event1, event2));

        List<RiskEventDTO> queue = service.getReviewQueue();

        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).getReviewStatus()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void getReviewQueue_sortsByExpectedLoss_scoreTimesAmount() {
        // $15的HIGH(1.0)=15 < $2000的MEDIUM(0.6)=1200 —— 金额大的可疑单排前面
        // a $2,000 MEDIUM outranks a $15 HIGH: expected loss 1200 vs 15
        RiskEvent smallHigh = buildEvent(30L, "ORD-SMALL", "HIGH", 1.0, "R", LocalDateTime.now());
        smallHigh.setAmount(15.0);
        RiskEvent bigMedium = buildEvent(31L, "ORD-BIG", "MEDIUM", 0.6, "R", LocalDateTime.now());
        bigMedium.setAmount(2000.0);
        RiskEvent nullScore = buildEvent(32L, "ORD-NULLS", "LOW", 0.0, "R", LocalDateTime.now());
        nullScore.setRiskScore(null);
        nullScore.setAmount(null);
        when(repository.findPendingReview()).thenReturn(List.of(smallHigh, nullScore, bigMedium));

        List<RiskEventDTO> queue = service.getReviewQueue();

        assertThat(queue).extracting(RiskEventDTO::getOrderId)
                .containsExactly("ORD-BIG", "ORD-SMALL", "ORD-NULLS");
    }

    // ── 幂等计数器：首次处理 → SETNX成功 → 计数+1 ─────────────────────────────
    // Idempotent counter: first delivery sets the marker and increments
    @Test
    void incrementNormalCounterIdempotent_firstDelivery_increments() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("processed:normal:ORD-1"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(true);

        service.incrementNormalCounterIdempotent("ORD-1");

        verify(valueOps).increment("counter:normal_orders");
    }

    // ── 幂等计数器：重复投递 → SETNX失败 → 不重复计数 ──────────────────────────
    // Idempotent counter: redelivery finds the marker already set and does NOT increment
    @Test
    void incrementNormalCounterIdempotent_redelivery_doesNotDoubleCount() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("processed:normal:ORD-1"), eq("1"), any(java.time.Duration.class)))
                .thenReturn(false);

        service.incrementNormalCounterIdempotent("ORD-1");

        verify(valueOps, never()).increment(anyString());
    }

    // ── 规则精度统计 / Rule precision from review labels ──────────────────────

    @Test
    void getRulePrecision_computesPerRuleCountsAndPrecision() {
        event1.setTriggeredRules("BlacklistRule");
        event1.setReviewStatus("CONFIRMED_FRAUD");
        event2.setTriggeredRules("BlacklistRule,HighAmountNewUserRule");
        event2.setReviewStatus("FALSE_POSITIVE");
        event3.setTriggeredRules("BlacklistRule");
        event3.setReviewStatus(null); // 未审核 / not yet reviewed
        when(repository.findAll()).thenReturn(List.of(event1, event2, event3));

        var stats = service.getRulePrecision();

        var blacklist = stats.stream().filter(s -> s.getRule().equals("BlacklistRule")).findFirst().orElseThrow();
        assertThat(blacklist.getTotalHits()).isEqualTo(3);
        assertThat(blacklist.getReviewedHits()).isEqualTo(2);
        assertThat(blacklist.getConfirmedFraud()).isEqualTo(1);
        assertThat(blacklist.getFalsePositive()).isEqualTo(1);
        assertThat(blacklist.getPrecision()).isEqualTo(50.0); // 1 of 2 reviewed hits was real

        var highAmount = stats.stream().filter(s -> s.getRule().equals("HighAmountNewUserRule")).findFirst().orElseThrow();
        assertThat(highAmount.getTotalHits()).isEqualTo(1);
        assertThat(highAmount.getPrecision()).isEqualTo(0.0); // its only reviewed hit was a false positive
    }

    @Test
    void getRulePrecision_noReviewedHits_precisionIsNull() {
        // 无标注时精度未知（null），绝不能显示成0%——那会误导ops认为规则全错
        // With no labels precision is unknown (null), never 0% — that would falsely
        // tell ops the rule is always wrong
        event1.setTriggeredRules("FrequentIpRule");
        event1.setReviewStatus("PENDING_REVIEW");
        when(repository.findAll()).thenReturn(List.of(event1));

        var stats = service.getRulePrecision();

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).getPrecision()).isNull();
        assertThat(stats.get(0).getTotalHits()).isEqualTo(1);
        assertThat(stats.get(0).getReviewedHits()).isEqualTo(0);
    }

    @Test
    void getRulePrecision_approvedCountsAgainstTheRule() {
        // APPROVED订单被放行 → 对规则而言同样是误报
        // An APPROVED order was allowed — from the rule's view that's also a false alarm
        event1.setTriggeredRules("AbnormalAmountRule");
        event1.setReviewStatus("APPROVED");
        when(repository.findAll()).thenReturn(List.of(event1));

        var stats = service.getRulePrecision();

        assertThat(stats.get(0).getApproved()).isEqualTo(1);
        assertThat(stats.get(0).getPrecision()).isEqualTo(0.0);
    }
}
