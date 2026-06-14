package com.fraudshield.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RiskResult {
    private String orderId;
    private RiskLevel riskLevel;
    private Double riskScore;          // 0.0 (clean) → 1.0 (definite fraud)
    private List<String> triggeredRules;
    private String explanation;
}
