package com.fraudshield.controller;

import com.fraudshield.model.ReviewAuditRecord;
import com.fraudshield.service.AuditChainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 审计链查询 — 审核决定的防篡改记录
 * Audit chain endpoints: the tamper-evident record of review decisions.
 */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditChainService auditChainService;

    public AuditController(AuditChainService auditChainService) {
        this.auditChainService = auditChainService;
    }

    // GET /api/audit — the full decision trail, oldest first
    @GetMapping
    public ResponseEntity<List<ReviewAuditRecord>> getChain() {
        return ResponseEntity.ok(auditChainService.getChain());
    }

    // GET /api/audit/verify — recompute every hash; reports the first broken link if any
    @GetMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify() {
        return ResponseEntity.ok(auditChainService.verifyChain());
    }
}
