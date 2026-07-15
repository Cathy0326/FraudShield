package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 单条AML典型手法（typology）的命中结果 —— 对应欺诈侧一条规则的输出。
 * One AML typology's finding — the finance-side counterpart of a single fraud rule's
 * output. score is 0..1 (strength of the laundering signal); explanation is written to
 * read straight into a SAR narrative.
 */
@Data
@Builder
public class AmlSignal {
    private String typology;      // e.g. "Structuring", "SanctionsScreening"
    private double score;         // 0..1
    private String explanation;
}
