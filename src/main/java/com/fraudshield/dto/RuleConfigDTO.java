package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 规则配置面板的一行 —— 把"诊断"（精度）和"操作"（启用/权重）并列，让风控负责人在同一处
 * 看到规则表现并直接调整。
 * One row of the rule-tuning console — puts the diagnosis (precision) next to the
 * controls (enabled / weight) so a fraud lead sees how a rule performs and tunes it
 * in the same place.
 */
@Data
@Builder
public class RuleConfigDTO {
    private String rule;
    private boolean enabled;
    private Double weightOverride;   // null → using the auto weight
    private double autoWeight;       // precision-derived (RuleWeightService)
    private double effectiveWeight;  // weightOverride when set, else autoWeight
    private Double precision;        // null until at least one hit is reviewed
    private long totalHits;
    private long reviewedHits;
}
