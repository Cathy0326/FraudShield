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

    // 唯一约束是幂等性的最终防线：check-then-insert在并发重复投递下有竞态，
    // 由DB层保证同一orderId不会写入两行
    // Unique constraint is the idempotency backstop: check-then-insert races under
    // concurrent redelivery, so the DB enforces one row per orderId.
    @Column(unique = true)
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

    // ── 人工审核工作流字段 ──────────────────────────────────────────────────
    // Review workflow: detection flags an order, but the business process is
    // detect → review → decide. A reviewer resolves each flagged event to
    // CONFIRMED_FRAUD / FALSE_POSITIVE / APPROVED; labels feed rule-precision stats.
    //
    // PENDING_REVIEW → CONFIRMED_FRAUD | FALSE_POSITIVE | APPROVED (terminal)
    // 存储枚举名字符串，与riskLevel同理 / stored as String, same rationale as riskLevel.
    @Builder.Default
    private String reviewStatus = "PENDING_REVIEW";

    private String reviewedBy;
    private LocalDateTime reviewedAt;
    @Column(length = 1024)
    private String reviewNotes;

    // ── AI增强字段（仅MEDIUM风险订单填充）──────────────────────────────────────
    // AI-enriched fields — populated only for MEDIUM-risk orders via Azure OpenAI

    private String aiRiskLevel;       // LLM的风险等级判断 / LLM's risk verdict
    private Double aiConfidence;      // 置信度 0.0–1.0 / LLM confidence
    @Column(length = 2048)
    private String aiReasoning;       // LLM推理说明 / free-text reasoning
    private String aiRecommendation;  // 建议操作 / recommended action
    @Column(length = 1024)
    private String aiKeyFactors;      // 关键因子 CSV / key factors, comma-separated
    private Boolean aiEnhanced;       // 是否完成AI分析 / true when AI call succeeded
}
