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

    /**
     * Entity → DTO 转换工厂方法
     * Factory: converts the comma-separated triggeredRules string back to a List.
     * This is the DTO pattern in action — DB format ≠ API format.
     */
    public static RiskEventDTO fromEntity(RiskEvent e) {
        List<String> rules = (e.getTriggeredRules() == null || e.getTriggeredRules().isBlank())
                ? List.of()
                : Arrays.asList(e.getTriggeredRules().split(","));

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
                .build();
    }
}
