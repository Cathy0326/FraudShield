package com.fraudshield.rule;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskResult;

public interface RiskRule {

    RiskResult evaluate(Order order);

    // Default: use the concrete class name as the rule identifier.
    // Implementations can override this for a friendlier display name.
    default String getRuleName() {
        return this.getClass().getSimpleName();
    }
}
