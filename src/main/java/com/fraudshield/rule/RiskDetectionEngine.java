package com.fraudshield.rule;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class RiskDetectionEngine {

    private final List<RiskRule> rules;
    private final MeterRegistry meterRegistry;

    // Spring auto-injects every @Component that implements RiskRule.
    // Adding a new rule class is enough — no wiring change needed here.
    public RiskDetectionEngine(List<RiskRule> rules, MeterRegistry meterRegistry) {
        this.rules = rules;
        this.meterRegistry = meterRegistry;
    }

    public RiskResult evaluate(Order order) {
        Timer.Sample sample = Timer.start(meterRegistry);
        RiskResult result = rules.stream()
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
        sample.stop(meterRegistry.timer("fraudshield.risk.evaluation.duration"));
        meterRegistry.counter("fraudshield.risk.events.total",
                "riskLevel", result.getRiskLevel().name()).increment();
        return result;
    }
}
