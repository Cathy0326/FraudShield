package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.rule.RiskRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 黑名单规则：userId 或 ipAddress 命中黑名单 → HIGH
 * Blacklist rule: userId OR ipAddress found in blacklist → HIGH
 *
 * 为什么用Redis SET (Why Redis SET over a List):
 *   SISMEMBER是O(1)操作，不管黑名单有100条还是100万条，速度一样快。
 *   SISMEMBER is O(1) regardless of blacklist size — 100 or 1,000,000 entries, same speed.
 *   A List would require O(n) scan, which is unacceptable for real-time fraud checks.
 *
 * Redis结构 (Redis structure):
 *   Key: "blacklist:users"   Type: Set   Members: userId strings
 *   Key: "blacklist:ips"     Type: Set   Members: IP address strings
 */
@Component
public class BlacklistRule implements RiskRule {

    private static final String BLACKLIST_USERS = "blacklist:users";
    private static final String BLACKLIST_IPS   = "blacklist:ips";

    private final StringRedisTemplate redisTemplate;

    public BlacklistRule(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RiskResult evaluate(Order order) {
        // SISMEMBER: O(1)精确匹配 / O(1) exact membership test
        Boolean userBlacklisted = redisTemplate.opsForSet().isMember(BLACKLIST_USERS, order.getUserId());
        Boolean ipBlacklisted   = redisTemplate.opsForSet().isMember(BLACKLIST_IPS,   order.getIpAddress());

        if (Boolean.TRUE.equals(userBlacklisted)) {
            return highRiskResult(order, "blacklist:users (userId=" + order.getUserId() + ")");
        }
        if (Boolean.TRUE.equals(ipBlacklisted)) {
            return highRiskResult(order, "blacklist:ips (ip=" + order.getIpAddress() + ")");
        }

        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(RiskLevel.NORMAL)
                .riskScore(0.0)
                .triggeredRules(List.of())
                .explanation("No blacklist match")
                .build();
    }

    private RiskResult highRiskResult(Order order, String matched) {
        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(RiskLevel.HIGH)
                .riskScore(1.0)
                .triggeredRules(List.of(getRuleName()))
                .explanation("Matched " + matched)
                .build();
    }
}
