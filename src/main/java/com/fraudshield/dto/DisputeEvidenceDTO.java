package com.fraudshield.dto;

import com.fraudshield.model.ReviewAuditRecord;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 争议证据包 — 商户打chargeback官司时提交给发卡行/支付网关的一站式证据
 * Dispute evidence package: everything a merchant submits when fighting a
 * chargeback — assembled from data the system already holds.
 *
 * <p>内容 = 订单事实 + 风控判定（命中规则与解释、AI分析）+ 人工审核决定 +
 * 防篡改审计链记录及全链完整性校验结果。审计链哈希是关键增值：它证明
 * "审核决定自做出后未被修改过"，让证据在争议流程中更有分量。
 * Contents = order facts + risk verdict (rules, explanation, AI analysis) +
 * the human review decision + tamper-evident audit-chain records with a
 * whole-chain integrity check. The chain hashes are the differentiator: they
 * attest the review decision has not been altered since it was made, which is
 * exactly what gives the evidence weight in a dispute.
 */
@Data
@Builder
public class DisputeEvidenceDTO {

    // ── 生成元数据 / generation metadata ────────────────────────────────
    private LocalDateTime generatedAt;
    private String generatedBy;      // 认证主体，非请求参数 / from the authenticated principal

    // ── 订单 + 风控判定 + 审核决定（全部字段随事件走）──────────────────────
    // Order facts, risk verdict, AI analysis and review decision all travel
    // inside the event DTO — no duplication here.
    private RiskEventDTO event;

    // ── 审计链证据 / audit-chain attestation ────────────────────────────
    // 该订单的链上记录（含prevHash/recordHash，可独立复核）
    // This order's chain links, hashes included so they can be re-verified independently
    private List<ReviewAuditRecord> auditTrail;
    private boolean chainValid;      // 全链完整性校验结果 / whole-chain integrity check
    private long chainRecordCount;   // 校验覆盖的记录总数 / records covered by the check
}
