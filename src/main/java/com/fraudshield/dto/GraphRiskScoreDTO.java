package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 图传播风险分数 — 用户在用户–IP图上离已确认欺诈节点有多"近"
 * Graph-propagated risk score: how close a user sits to confirmed fraud
 * on the user–IP graph. 1.0 = confirmed fraudster themselves; scores decay
 * with each hop of separation.
 */
@Data
@Builder
public class GraphRiskScoreDTO {
    private String userId;
    private double score;               // [0,1] — risk concentration at this node
    private boolean confirmedFraudster; // true when the user IS a seed, not just near one
}
