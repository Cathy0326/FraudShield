package com.fraudshield.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 审核审计记录 — 哈希链上的一环
 * One link in the tamper-evident review audit chain.
 *
 * <p>每条记录的recordHash覆盖了本条内容和前一条的recordHash（HMAC-SHA256）。
 * 改动任何历史记录都会使其后所有记录的哈希失配 —— 篡改不可能不留痕。
 * Each record's hash covers its own fields AND the previous record's hash
 * (HMAC-SHA256). Altering any historical record breaks every hash after it —
 * tampering cannot go unnoticed.
 */
@Entity
@Table(name = "review_audit_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderId;
    private String decision;
    private String reviewer;
    private LocalDateTime decidedAt;

    @Column(length = 64)
    private String prevHash;

    @Column(length = 64)
    private String recordHash;
}
