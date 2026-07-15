package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 规则×小时热力图 — 回答"哪条规则在什么时段爆发"。
 * Rule × hour heatmap: which rule fires when. The columns are a fixed, contiguous
 * run of hour buckets (oldest→newest) so the frontend can render a dense grid
 * without reconciling ragged per-rule time axes; each row's counts align 1:1 to
 * {@link #hours}. peak is the single busiest cell across the whole grid, used to
 * normalize the color scale.
 */
@Data
@Builder
public class RuleHeatmapDTO {
    private List<String> hours;     // column labels, e.g. "14:00", oldest first
    private List<RuleRow> rules;    // one row per rule, busiest overall first
    private long peak;              // max cell value in the grid (0 if empty)

    @Data
    @Builder
    public static class RuleRow {
        private String rule;
        private long[] counts;      // aligned to hours
        private long total;         // row sum, for the trailing total column
    }
}
