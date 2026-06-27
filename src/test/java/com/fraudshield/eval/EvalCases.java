package com.fraudshield.eval;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Fixed eval set for the fraud-explanation LLM call. Each case pairs a
 * synthetic order/rule-engine result with the recommendation a reasonable
 * reviewer would expect. Covers the full RiskLevel range plus a couple of
 * "messy signal" cases (conflicting score vs. rules) that are the actual
 * point of having an LLM second opinion in the first place.
 */
public final class EvalCases {

    private EvalCases() {
    }

    public static List<EvalCase> all() {
        return List.of(
                new EvalCase(
                        "clean-order-normal",
                        order("ORD-1001", "user-1", 42.50, "203.0.113.10", "device-aaa"),
                        risk("ORD-1001", RiskLevel.NORMAL, 0.02, List.of(), "No rules triggered"),
                        "allow"
                ),
                new EvalCase(
                        "minor-anomaly-low",
                        order("ORD-1002", "user-2", 120.00, "203.0.113.11", "device-bbb"),
                        risk("ORD-1002", RiskLevel.LOW, 0.25, List.of("FrequentIpRule"),
                                "Same IP used by 3 accounts in the last hour"),
                        "monitor"
                ),
                new EvalCase(
                        "high-amount-new-user-medium",
                        order("ORD-1003", "user-new-1", 980.00, "198.51.100.5", "device-ccc"),
                        risk("ORD-1003", RiskLevel.MEDIUM, 0.55, List.of("HighAmountNewUserRule"),
                                "New account's first order is 9x the typical first-order amount"),
                        "manual_review"
                ),
                new EvalCase(
                        "blacklisted-ip-high",
                        order("ORD-1004", "user-3", 350.00, "192.0.2.99", "device-ddd"),
                        risk("ORD-1004", RiskLevel.HIGH, 0.95, List.of("BlacklistRule"),
                                "Order IP address matches a known fraud blacklist entry"),
                        "block"
                ),
                new EvalCase(
                        "extreme-amount-high",
                        order("ORD-1005", "user-4", 15000.00, "198.51.100.20", "device-eee"),
                        risk("ORD-1005", RiskLevel.HIGH, 0.88, List.of("AbnormalAmountRule"),
                                "Order amount is 40x this user's historical average"),
                        "block"
                ),
                new EvalCase(
                        "conflicting-signal-low-score-high-level",
                        order("ORD-1006", "user-5", 60.00, "203.0.113.50", "device-fff"),
                        risk("ORD-1006", RiskLevel.HIGH, 0.30, List.of("BlacklistRule"),
                                "Low-value order but IP is on the fraud blacklist"),
                        "block"
                ),
                new EvalCase(
                        "borderline-medium-low-boundary",
                        order("ORD-1007", "user-6", 200.00, "203.0.113.60", "device-ggg"),
                        risk("ORD-1007", RiskLevel.MEDIUM, 0.48, List.of("FrequentIpRule"),
                                "Mild frequency anomaly, just over the rule threshold"),
                        "manual_review"
                )
        );
    }

    private static Order order(String orderId, String userId, double amount, String ip, String deviceId) {
        return new Order(orderId, userId, amount, ip, deviceId, LocalDateTime.now());
    }

    private static RiskResult risk(String orderId, RiskLevel level, double score,
                                    List<String> triggeredRules, String explanation) {
        return RiskResult.builder()
                .orderId(orderId)
                .riskLevel(level)
                .riskScore(score)
                .triggeredRules(triggeredRules)
                .explanation(explanation)
                .build();
    }
}
