package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 单条检测规则的精度统计 — 由人工审核标注计算得出
 * Precision stats for one detection rule, computed from human review labels.
 *
 * <p>precision = confirmedFraud / (confirmedFraud + falsePositive + approved):
 * APPROVED与FALSE_POSITIVE对规则而言都是误报（订单最终被放行）。
 * From the rule's perspective both APPROVED and FALSE_POSITIVE are false alarms —
 * the order was ultimately allowed. Null when no hits have been reviewed yet.
 */
@Data
@Builder
public class RulePrecisionDTO {
    private String rule;
    private long totalHits;        // 该规则命中的全部事件数 / all events this rule fired on
    private long reviewedHits;     // 其中已有人工结论的 / of those, human-decided
    private long confirmedFraud;
    private long falsePositive;
    private long approved;
    private Double precision;      // null until at least one hit is reviewed
}
