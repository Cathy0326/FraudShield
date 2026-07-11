package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.rule.RiskRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 地址模式检测 — 代收点/转运骡子（drop address / reshipping mule）识别
 * Address-pattern detection: the drop-address / reshipping-mule signal.
 *
 * <p>攻击画像：欺诈者用多张被盗卡、多个假身份下单，但把货全部发往同一个受控地址
 * （代收点或转运骡子的住址），再转运销赃。表层每笔订单的持卡人、账单地址都不同，
 * 看起来互不相关 —— 唯一的交汇点是收货地址。
 * The attacker orders with many stolen cards under many fake identities but ships
 * everything to one controlled address — a drop point or a reshipping mule's home —
 * then forwards the goods. On the surface each order has a different cardholder and
 * billing address and looks unrelated; the single convergence point is where the
 * goods physically go.
 *
 * <p>信号：同一收货地址在24小时窗口内出现 ≥3 个不同的userId → HIGH。
 * 这与CardTestingRule的"多身份+单一来源"同构，但来源是**物理收货地址**而非IP/设备，
 * 且不限金额（骡子代收的是真实货物，往往是高价电子产品）。
 * Signal: one shipping address seen with >=3 distinct userIds in a 24h window → HIGH.
 * Structurally this mirrors CardTestingRule's "many identities, one source", but the
 * source is a physical shipping address rather than an IP/device, and it is NOT
 * amount-restricted — mules receive real, usually high-value, goods.
 *
 * <p>账单≠收货（AVS不符）作为**辅助**信号：单独出现不触发（礼物、代购都会让二者
 * 不同，误报率高），但当drop-address模式已命中时，账单地址不符会强化说明。
 * Billing≠shipping (an AVS mismatch) is a SECONDARY signal: on its own it does not
 * fire — gifts and buying-on-behalf routinely differ, so it is noisy — but when the
 * drop-address pattern already matches, a mismatch reinforces the explanation.
 *
 * <p>Redis数据结构 (Redis data structure):
 *   Key:    "addr:ship:{normalizedAddress}"
 *   Type:   Sorted Set (ZSET)
 *   Member: "{userId}|{orderId}"  — orderId保证成员唯一，userId用于统计身份数
 *   Score:  System.currentTimeMillis()
 */
@Component
public class AddressPatternRule implements RiskRule {

    // 同一收货地址窗口内 ≥3 个不同身份即视为代收点 / >=3 identities to one address = drop point
    static final int MIN_DISTINCT_USERS = 3;
    private static final long WINDOW_MS = Duration.ofHours(24).toMillis();
    private static final long KEY_TTL_HOURS = 25;
    private static final String KEY_PREFIX = "addr:ship:";
    private static final String MEMBER_SEPARATOR = "|";

    private final StringRedisTemplate redisTemplate;

    public AddressPatternRule(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RiskResult evaluate(Order order) {
        String shipping = normalize(order.getShippingAddress());
        // 没有收货地址无从判断 —— 旧订单和不提供地址的调用方直接放行
        // No shipping address, nothing to assess — legacy orders and callers that
        // don't supply one pass through untouched
        if (shipping.isEmpty()) {
            return clean(order, "No shipping address to assess");
        }

        String key = KEY_PREFIX + shipping;
        long now = System.currentTimeMillis();

        ZSetOperations<String, String> zset = redisTemplate.opsForZSet();
        zset.add(key, order.getUserId() + MEMBER_SEPARATOR + order.getOrderId(), now);
        zset.removeRangeByScore(key, 0, now - WINDOW_MS);
        redisTemplate.expire(key, KEY_TTL_HOURS, TimeUnit.HOURS);

        Set<String> members = zset.range(key, 0, -1);
        long distinctUsers = members == null ? 0 : members.stream()
                .map(m -> m.substring(0, Math.max(0, m.indexOf(MEMBER_SEPARATOR))))
                .distinct()
                .count();

        boolean billingMismatch = hasBillingMismatch(order);

        if (distinctUsers >= MIN_DISTINCT_USERS) {
            String explanation = String.format(
                    "Drop-address pattern: shipping address used by %d different user "
                            + "identities in the last 24 hours (reshipping-mule signal)",
                    distinctUsers);
            if (billingMismatch) {
                explanation += "; billing address differs from shipping address (AVS mismatch)";
            }
            return RiskResult.builder()
                    .orderId(order.getOrderId())
                    .riskLevel(RiskLevel.HIGH)
                    .riskScore(0.9)
                    .triggeredRules(List.of(getRuleName()))
                    .explanation(explanation)
                    .build();
        }

        // 账单不符单独出现不触发 —— 保持精度，避免礼物/代购误报
        // Billing mismatch alone does not fire — preserves precision against gift orders
        return clean(order, "No drop-address pattern detected");
    }

    /**
     * 账单地址与收货地址是否不同（两者都存在时才有意义）。
     * Whether billing differs from shipping (only meaningful when both are present).
     */
    private boolean hasBillingMismatch(Order order) {
        String billing = normalize(order.getBillingAddress());
        String shipping = normalize(order.getShippingAddress());
        return !billing.isEmpty() && !shipping.isEmpty() && !billing.equals(shipping);
    }

    // 归一化：去首尾空白、压缩内部空白、转小写 —— "1 Main St " 与 "1  main st" 视为同一地址
    // Normalize: trim, collapse inner whitespace, lowercase — so trivial formatting
    // differences don't let a drop address dodge the match
    private String normalize(String address) {
        if (address == null) {
            return "";
        }
        return address.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private RiskResult clean(Order order, String explanation) {
        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(RiskLevel.NORMAL)
                .riskScore(0.0)
                .triggeredRules(List.of())
                .explanation(explanation)
                .build();
    }
}
