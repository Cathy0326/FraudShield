package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.rule.RiskRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 异常金额规则：订单金额 > 用户历史均值 * 3 → MEDIUM
 * Abnormal amount rule: order amount > 3x user's historical average → MEDIUM
 *
 * 指数移动平均 (Exponential Moving Average, EMA):
 *   newAvg = oldAvg * 0.9 + newAmount * 0.1
 *
 * 为什么用EMA不用简单平均 (Why EMA over simple average):
 *   - 简单平均需要存所有历史订单 → 内存爆炸  (simple avg needs all history → memory explosion)
 *   - EMA只存一个数字，自动给近期数据更高权重  (EMA stores ONE number; recent orders weigh more)
 *   - 空间复杂度O(1)，适合流式统计  (O(1) space — ideal for streaming stats)
 *   - 0.9衰减因子让旧数据逐渐淡出  (0.9 decay factor lets old data fade exponentially)
 *
 * Redis结构 (Redis structure):
 *   Key:   "user:avg_amount:{userId}"   Type: String
 *   Value: double as string, e.g. "50.0"
 *   TTL:   30 days (reset on each update)
 */
@Component
public class AbnormalAmountRule implements RiskRule {

    private static final double SPIKE_MULTIPLIER = 3.0;
    private static final double EMA_DECAY = 0.9;       // weight on historical average
    private static final double EMA_NEW   = 0.1;       // weight on new data point
    private static final long   TTL_DAYS  = 30;
    private static final String KEY_PREFIX = "user:avg_amount:";

    private final StringRedisTemplate redisTemplate;

    public AbnormalAmountRule(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RiskResult evaluate(Order order) {
        String key = KEY_PREFIX + order.getUserId();
        String avgStr = redisTemplate.opsForValue().get(key);

        RiskResult result;

        if (avgStr == null) {
            // 没有历史数据 → 跳过此规则，返回NORMAL
            // No history yet → skip rule, can't determine abnormality
            result = normalResult(order);
        } else {
            double average = Double.parseDouble(avgStr);

            if (order.getAmount() > average * SPIKE_MULTIPLIER) {
                result = RiskResult.builder()
                        .orderId(order.getOrderId())
                        .riskLevel(RiskLevel.MEDIUM)
                        .riskScore(0.65)
                        .triggeredRules(List.of(getRuleName()))
                        .explanation(String.format(
                                "Amount %.2f exceeds 3x user average %.2f",
                                order.getAmount(), average))
                        .build();
            } else {
                result = normalResult(order);
            }

            // 无论是否触发，都更新EMA（让均值跟随用户行为演变）
            // Always update EMA so the average evolves with user behavior
            double newAverage = average * EMA_DECAY + order.getAmount() * EMA_NEW;
            redisTemplate.opsForValue().set(key, String.valueOf(newAverage), TTL_DAYS, TimeUnit.DAYS);
        }

        return result;
    }

    private RiskResult normalResult(Order order) {
        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(RiskLevel.NORMAL)
                .riskScore(0.0)
                .triggeredRules(List.of())
                .explanation("Amount within normal range")
                .build();
    }
}
