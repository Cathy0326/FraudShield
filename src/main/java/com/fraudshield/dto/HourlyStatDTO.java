package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HourlyStatDTO {
    private String hour;        // e.g. "2026-06-14 10:00"
    private Long orderCount;
    private Long riskCount;

    // 基线：过去7天同一小时段风险单数的均值与标准差 —— 回答"和正常水平比怎么样"
    // Baseline: mean and std-dev of risk counts for this same hour-of-day over the
    // prior 7 days - answers "compared to what?" for the trend chart.
    private Double baselineRisk;
    private Double baselineSigma;
}
