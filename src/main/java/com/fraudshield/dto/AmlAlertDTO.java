package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一笔交易的AML筛查结论 —— 把命中的典型手法组合成一个可执行的告警。
 * The AML verdict for one transaction: the fired typologies combined into one
 * actionable alert. alertLevel/recommendedAction map the combined score onto the
 * investigator's next step; narrative is a ready-to-paste SAR summary. This mirrors
 * the fraud side's RiskResult but speaks in compliance terms (escalate → file a SAR).
 */
@Data
@Builder
public class AmlAlertDTO {
    private String transactionId;
    private double score;               // combined 0..1
    private String alertLevel;          // CLEAR / MONITOR / ESCALATE / FILE_SAR
    private String recommendedAction;   // human-readable next step
    private List<AmlSignal> typologies; // which typologies fired
    private String narrative;           // SAR-ready summary sentence
}
