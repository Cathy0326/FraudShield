package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class DashboardStatsDTO {
    private Long totalOrders;              // persisted risk events (HIGH + MEDIUM)
    private Long highRiskCount;
    private Long mediumRiskCount;
    private Long lowRiskCount;
    private Long normalCount;              // from Redis counter (not persisted to DB)
    private Double riskRate;              // (high + medium) / total * 100
    private Map<String, Long> ruleHitCounts;  // per-rule trigger counts from DB
    private List<HourlyStatDTO> hourlyTrend;  // last 24 hours, one bucket per hour

    // 周同比：近7天 vs 之前7天的差值（正=恶化）。仅对已持久化的量计算 ——
    // NORMAL订单只在Redis计数、无时间戳，历史周的risk rate无法诚实还原
    // Week-over-week deltas (positive = worse), last 7d minus prior 7d. Only for
    // persisted quantities - NORMAL orders live in a Redis counter with no
    // timestamps, so a historical weekly risk rate can't be honestly reconstructed.
    private Long highRiskWowDelta;
    private Long mediumRiskWowDelta;
}
