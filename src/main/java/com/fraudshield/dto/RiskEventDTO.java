package com.fraudshield.dto;

import com.fraudshield.model.RiskEvent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Data
@Builder
public class RiskEventDTO {

    private Long id;
    private String orderId;
    private String userId;
    private String ipAddress;
    private Double amount;
    private String riskLevel;
    private Double riskScore;
    private List<String> triggeredRules;   // split from comma-separated DB field
    private String explanation;
    private LocalDateTime detectedAt;

    // AI增强字段 / AI-enriched fields (only present for MEDIUM-risk orders)
    private String aiRiskLevel;
    private Double aiConfidence;
    private String aiReasoning;
    private String aiRecommendation;
    private List<String> aiKeyFactors;
    private Boolean aiEnhanced;

    /**
     * Entity → DTO 转换工厂方法
     * Factory: converts comma-separated CSV strings back to Lists.
     */
    public static RiskEventDTO fromEntity(RiskEvent e) {
        List<String> rules = (e.getTriggeredRules() == null || e.getTriggeredRules().isBlank())
                ? List.of()
                : Arrays.asList(e.getTriggeredRules().split(","));

        List<String> keyFactors = (e.getAiKeyFactors() == null || e.getAiKeyFactors().isBlank())
                ? List.of()
                : Arrays.asList(e.getAiKeyFactors().split(","));

        return RiskEventDTO.builder()
                .id(e.getId())
                .orderId(e.getOrderId())
                .userId(e.getUserId())
                .ipAddress(e.getIpAddress())
                .amount(e.getAmount())
                .riskLevel(e.getRiskLevel())
                .riskScore(e.getRiskScore())
                .triggeredRules(rules)
                .explanation(e.getExplanation())
                .detectedAt(e.getDetectedAt())
                .aiRiskLevel(e.getAiRiskLevel())
                .aiConfidence(e.getAiConfidence())
                .aiReasoning(e.getAiReasoning())
                .aiRecommendation(e.getAiRecommendation())
                .aiKeyFactors(keyFactors)
                .aiEnhanced(e.getAiEnhanced())
                .build();
    }
}
