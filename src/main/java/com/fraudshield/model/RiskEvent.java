package com.fraudshield.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "risk_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderId;
    private String userId;
    private String ipAddress;
    private Double amount;

    // 存储枚举名称字符串，方便SQL查询和未来迁移
    // Store as String so SQL queries are readable and enum renames don't break the schema
    private String riskLevel;

    private Double riskScore;

    // List<String> → comma-separated; avoids a join table for a simple field
    @Column(length = 1024)
    private String triggeredRules;

    @Column(length = 1024)
    private String explanation;

    @CreationTimestamp
    private LocalDateTime detectedAt;
}
