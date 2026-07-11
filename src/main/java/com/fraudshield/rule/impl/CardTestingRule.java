package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.rule.RiskRule;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 卡测试（Card Testing / "John Doe"攻击）检测规则
 * Card-testing detection: the "John Doe" attack pattern.
 *
 * <p>攻击画像：欺诈者拿到一批被盗卡号后，先用小额订单（$0.5–$10）逐张试卡，
 * 确认哪些卡还活着，再用活卡刷大额。为了绕开单账号限制，每笔小单挂在不同的
 * 假身份（John Doe、Jane Smith…）下 —— 但流量都来自同一个IP或同一台设备。
 * The attacker holds a batch of stolen card numbers and probes each with a tiny
 * charge to learn which cards are still live, then monetizes the live ones with
 * big purchases. To dodge per-account limits every probe uses a different fake
 * identity — but the traffic all comes from one IP or one device.
 *
 * <p>为什么FrequentIpRule抓不住它：那条规则只看"同IP订单数>5"，而卡测试
 * 攻击者可以放慢到每分钟一单绕开频率阈值。本规则看的是**组合特征**：
 * 小额 + 多身份 + 同来源 —— 正常家庭共享NAT会产生多身份同IP，但不会全是小额；
 * 单人快速下单会同IP小额，但不会换身份。三个条件同时满足几乎只有卡测试。
 * Why FrequentIpRule misses this: it only counts orders per IP, and a card tester
 * can slow down below the frequency threshold. This rule keys on the COMBINATION:
 * micro-amounts + many identities + one source. A shared-NAT household gives many
 * identities but not all micro-charges; one impatient shopper gives micro-charges
 * but a single identity. All three together is almost uniquely card testing.
 *
 * <p>Redis数据结构 (Redis data structure):
 *   Key:    "cardtest:ip:{ip}" / "cardtest:device:{deviceId}"
 *   Type:   Sorted Set (ZSET)
 *   Member: "{userId}|{orderId}"  — orderId保证成员唯一，userId用于统计身份数
 *                                   orderId keeps members unique; userId lets us
 *                                   count distinct identities in the window
 *   Score:  System.currentTimeMillis()
 *
 * 只有小额订单才进入集合 —— 大额订单与卡测试无关，不应污染窗口统计。
 * Only micro-charges enter the set: large orders are irrelevant to card testing
 * and must not pollute the window counts.
 */
@Component
public class CardTestingRule implements RiskRule {

    // 卡测试探测单通常在$10以下 / probe charges are almost always under $10
    static final double SMALL_AMOUNT_MAX = 10.0;
    // 窗口内 ≥4笔小额 且 ≥3个不同身份 才命中 —— 单独任一条件都有正常解释
    // >=4 micro-charges AND >=3 distinct identities; either alone has innocent explanations
    static final int MIN_SMALL_ORDERS = 4;
    static final int MIN_DISTINCT_USERS = 3;

    private static final long WINDOW_MS = Duration.ofMinutes(10).toMillis();
    private static final long KEY_TTL_MINUTES = 20;
    private static final String IP_KEY_PREFIX = "cardtest:ip:";
    private static final String DEVICE_KEY_PREFIX = "cardtest:device:";
    private static final String MEMBER_SEPARATOR = "|";

    private final StringRedisTemplate redisTemplate;

    public CardTestingRule(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RiskResult evaluate(Order order) {
        // 大额订单既不追踪也不评估 —— 本规则只关心小额探测流量
        // Large orders are neither tracked nor evaluated; this rule only watches probes
        if (order.getAmount() == null || order.getAmount() > SMALL_AMOUNT_MAX) {
            return clean(order, "Amount above card-testing probe range");
        }

        List<String> explanations = new ArrayList<>();
        checkDimension(IP_KEY_PREFIX, "IP", order.getIpAddress(), order, explanations);
        checkDimension(DEVICE_KEY_PREFIX, "device", order.getDeviceId(), order, explanations);

        if (!explanations.isEmpty()) {
            return RiskResult.builder()
                    .orderId(order.getOrderId())
                    .riskLevel(RiskLevel.HIGH)
                    .riskScore(0.95)
                    .triggeredRules(List.of(getRuleName()))
                    .explanation(String.join("; ", explanations))
                    .build();
        }
        return clean(order, "No card-testing pattern detected");
    }

    /**
     * 对单一维度（IP或设备）执行滑动窗口统计；命中时把说明追加到explanations。
     * Sliding-window bookkeeping for one dimension (IP or device); appends an
     * explanation when the pattern matches.
     */
    private void checkDimension(String keyPrefix, String dimensionName, String dimensionValue,
                                Order order, List<String> explanations) {
        if (dimensionValue == null || dimensionValue.isBlank()) {
            return;
        }
        String key = keyPrefix + dimensionValue;
        long now = System.currentTimeMillis();

        ZSetOperations<String, String> zset = redisTemplate.opsForZSet();
        zset.add(key, order.getUserId() + MEMBER_SEPARATOR + order.getOrderId(), now);
        zset.removeRangeByScore(key, 0, now - WINDOW_MS);
        redisTemplate.expire(key, KEY_TTL_MINUTES, TimeUnit.MINUTES);

        // 淘汰过旧成员后，剩下的全部在窗口内 —— 直接取全量解析身份数
        // After eviction everything left is in-window; fetch all members to count identities
        Set<String> members = zset.range(key, 0, -1);
        if (members == null || members.size() < MIN_SMALL_ORDERS) {
            return;
        }
        long distinctUsers = members.stream()
                .map(m -> m.substring(0, Math.max(0, m.indexOf(MEMBER_SEPARATOR))))
                .distinct()
                .count();
        if (distinctUsers >= MIN_DISTINCT_USERS) {
            explanations.add(String.format(
                    "Card-testing pattern on %s %s: %d micro-charges (<= $%.0f) from %d "
                            + "different user identities in the last 10 minutes",
                    dimensionName, dimensionValue, members.size(), SMALL_AMOUNT_MAX,
                    distinctUsers));
        }
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
