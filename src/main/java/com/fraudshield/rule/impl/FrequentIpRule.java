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
import java.util.concurrent.TimeUnit;

/**
 * 滑动窗口规则：同一IP在5分钟内下单超过5次 → HIGH风险
 * Sliding-window rule: more than 5 orders from the same IP within 5 minutes → HIGH risk
 *
 * Redis数据结构 (Redis data structure):
 *   Key:   "ip:order:{ipAddress}"          e.g. "ip:order:192.168.1.1"
 *   Type:  Sorted Set (ZSET)
 *   Member: orderId  (唯一标识每笔订单 / uniquely identifies each order)
 *   Score:  System.currentTimeMillis()     (毫秒时间戳作为排序依据 / ms timestamp as sort key)
 *
 * 滑动窗口逻辑 (Sliding window logic):
 *   1. ZADD  key now orderId          → 把当前订单加入集合 / add current order
 *   2. ZREMRANGEBYSCORE key 0 (now-5min) → 删除5分钟前的旧数据 / evict entries older than 5 min
 *   3. ZCARD key                      → 统计窗口内剩余订单数 / count orders still in window
 *   4. count > 5 → HIGH risk
 *
 * 为什么用ZSET不用普通计数器 (Why ZSET over a plain counter):
 *   普通counter只记总数，无法区分新旧数据。
 *   ZSET以时间戳为score，可以精确移除过期成员，窗口随时间向前"滑动"。
 *   A plain counter can't distinguish old from new. ZSET scores let us
 *   evict expired members precisely — the window truly slides forward.
 */
@Component
public class FrequentIpRule implements RiskRule {

    private static final int ORDER_LIMIT = 5;
    private static final long WINDOW_MS = Duration.ofMinutes(5).toMillis();
    private static final long KEY_TTL_MINUTES = 10;
    private static final String KEY_PREFIX = "ip:order:";

    private final StringRedisTemplate redisTemplate;

    public FrequentIpRule(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public RiskResult evaluate(Order order) {
        String key = KEY_PREFIX + order.getIpAddress();
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;

        ZSetOperations<String, String> zset = redisTemplate.opsForZSet();

        // 1. 将当前订单加入有序集合，score = 当前毫秒时间戳
        //    Add current order; score = current epoch ms
        zset.add(key, order.getOrderId(), now);

        // 2. 删除窗口外的旧成员（score < windowStart）
        //    Remove members whose score (timestamp) is before the window start
        zset.removeRangeByScore(key, 0, windowStart);

        // 3. 统计当前窗口内的订单数
        //    Count how many orders remain inside the 5-minute window
        Long count = zset.zCard(key);
        long orderCount = (count == null) ? 0 : count;

        // 4. 刷新TTL，避免key永久占用内存
        //    Refresh TTL so the key expires if the IP goes quiet
        redisTemplate.expire(key, KEY_TTL_MINUTES, TimeUnit.MINUTES);

        if (orderCount > ORDER_LIMIT) {
            return RiskResult.builder()
                    .orderId(order.getOrderId())
                    .riskLevel(RiskLevel.HIGH)
                    .riskScore(0.9)
                    .triggeredRules(List.of(getRuleName()))
                    .explanation(String.format(
                            "IP %s placed %d orders in the last 5 minutes (limit: %d)",
                            order.getIpAddress(), orderCount, ORDER_LIMIT))
                    .build();
        }

        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(RiskLevel.NORMAL)
                .riskScore(0.0)
                .triggeredRules(List.of())
                .explanation("IP order frequency within normal range")
                .build();
    }
}
