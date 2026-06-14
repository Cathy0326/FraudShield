package com.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * AI分析结果 POJO — 从Azure OpenAI响应中解析出来，不持久化到数据库
 * Parsed from the Azure OpenAI chat completion response; not persisted directly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiAnalysis {

    // AI判断的风险等级（可能与规则引擎不同，用于二次确认）
    // Risk level assessed by the LLM — may differ from the rule engine's verdict
    private String aiRiskLevel;

    // 0.0–1.0 置信度分数
    // LLM confidence score (0.0–1.0)
    private Double confidence;

    // 简短推理说明
    // Short free-text reasoning from the model
    private String reasoning;

    // 处置建议
    // Recommended action (e.g. "block", "manual review", "allow")
    private String recommendation;

    // 关键风险因子列表
    // Key factors that drove the risk assessment
    private List<String> keyFactors;

    // 标记此事件是否经过AI增强分析
    // True when the AI analysis completed without error
    private boolean aiEnhanced;
}
