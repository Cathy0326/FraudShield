package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Per-user fraud profile — a single order is evaluated against the rules in isolation,
 * but a reviewer deciding whether to trust a borderline order needs to see this user's
 * history, not just this one order.
 */
@Data
@Builder
public class UserRiskProfileDTO {
    private String userId;
    private long totalOrders;
    private long highRiskCount;
    private long mediumRiskCount;
    private long lowRiskCount;
    private double totalAmount;
    private List<RiskEventDTO> recentEvents;

    // Other userIds seen ordering from the same IP(s) as this user — a strong fraud-ring
    // signal (shared infrastructure across "different" accounts), distinct from anything
    // a single-order rule can see.
    private List<String> linkedUserIds;

    // 图传播分数：多跳团伙信号（linkedUserIds只能看两跳，这个能看整个连通分量）
    // Graph-propagated score: the multi-hop ring signal — linkedUserIds sees two hops,
    // this sees the whole connected component around confirmed fraud.
    private Double graphRiskScore;
}
