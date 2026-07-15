package com.fraudshield.aml;

import com.fraudshield.dto.AmlAlertDTO;
import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * AML检测引擎 —— 复用欺诈侧的noisy-OR组合思路，把命中的典型手法合成一个可执行告警。
 * AML detection engine: same noisy-OR combination the fraud engine uses, applied to
 * AML typologies. Spring auto-injects every AmlTypology @Component, so adding a
 * typology needs no change here (mirrors RiskDetectionEngine).
 *
 * <p>与欺诈打分的关键差异：制裁命中不是"统计上可疑"，而是**确定的可上报事件** ——
 * 一旦命中直接置为FILE_SAR，不参与阈值判断。
 * Key difference from fraud scoring: a sanctions hit isn't "statistically suspicious",
 * it's a definite reportable event — any sanctions match forces FILE_SAR regardless of
 * the combined score.
 */
@Service
public class AmlDetectionEngine {

    private final List<AmlTypology> typologies;

    public AmlDetectionEngine(List<AmlTypology> typologies) {
        this.typologies = typologies;
    }

    public AmlAlertDTO screen(Transaction txn) {
        List<AmlSignal> fired = typologies.stream()
                .map(t -> t.evaluate(txn))
                .filter(s -> s.getScore() > 0)
                .collect(Collectors.toList());

        // noisy-OR: combined = 1 − Π(1 − sᵢ)
        double survivor = 1.0;
        for (AmlSignal s : fired) {
            survivor *= 1.0 - Math.min(1.0, s.getScore());
        }
        double combined = Math.round((1.0 - survivor) * 100.0) / 100.0;

        boolean sanctionsHit = fired.stream().anyMatch(s -> s.getTypology().startsWith("Sanctions"));
        String level = levelFor(combined, sanctionsHit);

        return AmlAlertDTO.builder()
                .transactionId(txn.getTransactionId())
                .score(combined)
                .alertLevel(level)
                .recommendedAction(actionFor(level))
                .typologies(fired)
                .narrative(narrative(txn, fired, level))
                .build();
    }

    private String levelFor(double score, boolean sanctionsHit) {
        if (sanctionsHit || score >= 0.9) {
            return "FILE_SAR";
        }
        if (score >= 0.7) {
            return "ESCALATE";
        }
        if (score >= 0.4) {
            return "MONITOR";
        }
        return "CLEAR";
    }

    private String actionFor(String level) {
        return switch (level) {
            case "FILE_SAR"  -> "Open a case and file a SAR within the regulatory deadline";
            case "ESCALATE"  -> "Route to an investigator for enhanced due diligence";
            case "MONITOR"   -> "Keep under watch; re-score on the next transaction";
            default          -> "No action — cleared";
        };
    }

    private String narrative(Transaction txn, List<AmlSignal> fired, String level) {
        if (fired.isEmpty()) {
            return "No AML typology triggered.";
        }
        String reasons = fired.stream().map(AmlSignal::getExplanation).collect(Collectors.joining("; "));
        return String.format("Transaction %s (%s %s → %s): %s. Disposition: %s.",
                txn.getTransactionId(),
                txn.getAmount() == null ? "" : String.format("$%,.0f", txn.getAmount()),
                txn.getSenderAccount(), txn.getReceiverAccount(), reasons, level);
    }
}
