package com.fraudshield.eval;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskResult;

/**
 * One fixture for the AI fraud-explanation eval harness: a synthetic order +
 * rule-engine result, paired with the recommendation a human reviewer would
 * expect the LLM to land on. Not a hard oracle — the LLM is allowed to reason
 * about nuance the rule engine missed — but a wildly different recommendation
 * (e.g. "allow" on an obvious HIGH-risk case) is a real failure, not just
 * stylistic disagreement.
 */
public record EvalCase(
        String name,
        Order order,
        RiskResult riskResult,
        String expectedRecommendation
) {
}
