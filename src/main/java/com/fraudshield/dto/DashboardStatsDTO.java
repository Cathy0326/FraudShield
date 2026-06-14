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
}
