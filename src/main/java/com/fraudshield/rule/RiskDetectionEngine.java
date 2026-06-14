package com.fraudshield.rule;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskResult;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RiskDetectionEngine {

    private final List<RiskRule> rules;

    // Spring auto-injects every @Component that implements RiskRule.
    // Adding a new rule class is enough — no wiring change needed here.
    public RiskDetectionEngine(List<RiskRule> rules) {
        this.rules = rules;
    }

    public RiskResult evaluate(Order order) {
        return rules.stream()
                .map(rule -> rule.evaluate(order))
                .max(Comparator
                        // Primary sort: highest riskScore wins
                        .comparingDouble(RiskResult::getRiskScore)
                        // Tie-breaker: highest RiskLevel ordinal wins (NORMAL < LOW < MEDIUM < HIGH)
                        .thenComparingInt(r -> r.getRiskLevel().ordinal()))
                .orElseGet(() -> RiskResult.builder()
                        .orderId(order.getOrderId())
                        .riskLevel(com.fraudshield.model.RiskLevel.NORMAL)
                        .riskScore(0.0)
                        .triggeredRules(List.of())
                        .explanation("No rules configured")
                        .build());
    }
}
