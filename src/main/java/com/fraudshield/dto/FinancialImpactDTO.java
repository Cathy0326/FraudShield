package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 财务影响汇总 — 财务/管理层的问题："风控这个月拦住了多少钱？误杀了多少营收？"
 * Financial impact summary - finance's question: how much fraud loss did we
 * intercept, and how much legitimate revenue did we wrongly block?
 *
 * <p>interceptedAmount：确认欺诈订单的总额 = 避免的损失（每一分都会变成chargeback）。
 * falsePositiveAmount：误报订单的总额 = 我们错杀的真实营收。两者之比就是风控团队
 * ROI的一句话版本。
 * interceptedAmount = confirmed-fraud total (every dollar was a chargeback avoided);
 * falsePositiveAmount = wrongly blocked legitimate revenue. Their ratio is the
 * one-line ROI of the whole fraud program.
 */
@Data
@Builder
public class FinancialImpactDTO {
    private double interceptedAmount;     // confirmed fraud $ - losses avoided
    private double falsePositiveAmount;   // wrongly flagged $ - revenue cost
    private double approvedAmount;        // reviewed-and-released $
    private long interceptedCount;
    private long falsePositiveCount;
    private long approvedCount;
    // 拦截额 / 误杀额；误杀为0时为null（"∞"应显式表达而不是除零）
    // intercepted / false-positive dollars; null when nothing was wrongly killed
    private Double interceptToFalseKillRatio;
}
