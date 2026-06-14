package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.rule.RiskRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组合风险规则：新用户（账号创建 < 24小时）+ 高金额（> 100）→ MEDIUM
 * Compound risk: new account (< 24h old) AND high order amount (> 100) → MEDIUM
 *
 * 设计思想 (Design rationale):
 *   单因素不触发，两个因素同时出现才可疑。
 *   A single factor alone is not suspicious; the combination is.
 *   This is "correlated signal detection" — a core idea in real fraud systems.
 *
 * Redis结构 (Redis structure):
 *   Key:   "user:created:{userId}"   Type: String
 *   Value: epoch milliseconds of account creation time
 */
@Component
public class HighAmountNewUserRule implements RiskRule {

    private static final double AMOUNT_THRESHOLD = 100.0;
    private static final long NEW_USER_WINDOW_MS = 24 * 60 * 60 * 1000L; // 24 hours in ms
    private static final String KEY_PREFIX = "user:created:";

    private final StringRedisTemplate redisTemplate;

    public HighAmountNewUserRule(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RiskResult evaluate(Order order) {
        String key = KEY_PREFIX + order.getUserId();
        String createdAtStr = redisTemplate.opsForValue().get(key);

        long now = System.currentTimeMillis();
        boolean isNewUser;

        if (createdAtStr == null) {
            // 保守策略：Redis中没有创建时间 → 视为新用户
            // Conservative: no creation record found → treat as new user
            isNewUser = true;
        } else {
            long createdAt = Long.parseLong(createdAtStr);
            isNewUser = (now - createdAt) < NEW_USER_WINDOW_MS;
        }

        if (isNewUser && order.getAmount() > AMOUNT_THRESHOLD) {
            return RiskResult.builder()
                    .orderId(order.getOrderId())
                    .riskLevel(RiskLevel.MEDIUM)
                    .riskScore(0.6)
                    .triggeredRules(List.of(getRuleName()))
                    .explanation(String.format(
                            "New account (< 24h) with high order amount: %.2f", order.getAmount()))
                    .build();
        }

        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(RiskLevel.NORMAL)
                .riskScore(0.0)
                .triggeredRules(List.of())
                .explanation("No new-user high-amount signal")
                .build();
    }
}
