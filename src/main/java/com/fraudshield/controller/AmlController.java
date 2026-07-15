package com.fraudshield.controller;

import com.fraudshield.aml.AmlDetectionEngine;
import com.fraudshield.dto.AmlAlertDTO;
import com.fraudshield.model.Transaction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AML交易监控（finance视角切片）—— 与欺诈侧共用同一套"实时打分→人工复核→审计"骨架。
 * AML transaction monitoring (the finance-angle slice). Reuses the same real-time
 * score → human review → audit backbone as the fraud side.
 */
@RestController
@RequestMapping("/api/aml")
public class AmlController {

    private final AmlDetectionEngine engine;

    public AmlController(AmlDetectionEngine engine) {
        this.engine = engine;
    }

    /** 单笔交易实时筛查 / screen one transaction in real time. */
    @PostMapping("/screen")
    public ResponseEntity<AmlAlertDTO> screen(@RequestBody Transaction txn) {
        if (txn.getTransactionId() == null) {
            txn.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        if (txn.getTimestamp() == null) {
            txn.setTimestamp(LocalDateTime.now());
        }
        return ResponseEntity.ok(engine.screen(txn));
    }

    /**
     * 演示批次：合成一串交易（含一组结构化分拆、一笔制裁命中、以及若干干净交易），返回它们的
     * 告警 —— 一眼看到AML检测端到端在跑。
     * Demo batch: synthesize a mix (a structuring burst, a sanctions hit, and clean
     * traffic) and return their alerts, so AML detection is visible end-to-end.
     */
    @GetMapping("/demo")
    public ResponseEntity<List<AmlAlertDTO>> demo() {
        List<AmlAlertDTO> out = new ArrayList<>();

        // 1) 结构化分拆：同一账户24h内多笔刚好低于$10k → 累计超过上报线
        // Structuring: one account, several just-under-$10k transfers in 24h
        String mule = "ACC-MULE-4471";
        for (int i = 0; i < 4; i++) {
            out.add(engine.screen(Transaction.builder()
                    .transactionId("TXN-STR-" + i).senderAccount(mule).senderName("Dana Rowe")
                    .receiverAccount("ACC-OFFSHORE-9").receiverName("Quiet Bay Ltd")
                    .amount(9200.0 + i * 100).currency("USD").originCountry("US").destCountry("KY")
                    .channel("WIRE").timestamp(LocalDateTime.now()).build()));
        }
        // 2) 制裁命中 / sanctions hit
        out.add(engine.screen(Transaction.builder()
                .transactionId("TXN-SAN-1").senderAccount("ACC-1001").senderName("Acme Payroll")
                .receiverAccount("ACC-2002").receiverName("Viktor Petrov")
                .amount(4500.0).currency("USD").originCountry("US").destCountry("RU")
                .channel("WIRE").timestamp(LocalDateTime.now()).build()));
        // 3) 干净交易 / clean transactions
        out.add(engine.screen(Transaction.builder()
                .transactionId("TXN-OK-1").senderAccount("ACC-3003").senderName("Jane Miller")
                .receiverAccount("ACC-4004").receiverName("City Utilities")
                .amount(120.0).currency("USD").originCountry("US").destCountry("US")
                .channel("ACH").timestamp(LocalDateTime.now()).build()));

        return ResponseEntity.ok(out);
    }
}
