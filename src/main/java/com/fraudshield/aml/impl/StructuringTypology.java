package com.fraudshield.aml.impl;

import com.fraudshield.aml.AmlTypology;
import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 分拆交易（structuring / smurfing）—— 把一笔超过$10,000上报线的资金，拆成多笔刚好低于
 * 该线的划转，以规避货币交易报告（CTR）。
 * Structuring / smurfing: splitting a >$10,000 movement into several just-under-the-line
 * transfers to dodge the Currency Transaction Report (CTR) threshold.
 *
 * <p>检测（复用FrequentIpRule的Redis滑动窗口）：按发起账户，在24小时窗口内累计"分拆带"
 * ($3,000–$9,999.99)内的划转；当**笔数≥3且累计≥$10,000**时命中 —— 单看任一笔都合规，
 * 合起来才是刻意规避。成员存 "txnId|amount" 以便同时得到笔数和累计额。
 * Detection reuses FrequentIpRule's Redis sliding-window: per sender account, accumulate
 * transfers in the structuring band ($3k–$9,999.99) over 24h. It fires when count ≥ 3 AND
 * cumulative ≥ $10,000 — each transfer looks compliant alone; only together do they reveal
 * deliberate evasion. Members are stored as "txnId|amount" so one ZSET yields both count
 * and sum.
 */
@Component
public class StructuringTypology implements AmlTypology {

    private static final double CTR_THRESHOLD = 10_000.0;
    private static final double BAND_FLOOR = 3_000.0;          // ignore trivial amounts
    private static final int MIN_COUNT = 3;
    private static final long WINDOW_MS = Duration.ofHours(24).toMillis();
    private static final long KEY_TTL_HOURS = 25;
    private static final String KEY_PREFIX = "aml:struct:";

    private final StringRedisTemplate redis;

    public StructuringTypology(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public AmlSignal evaluate(Transaction txn) {
        double amount = txn.getAmount() == null ? 0.0 : txn.getAmount();
        String key = KEY_PREFIX + txn.getSenderAccount();
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MS;
        ZSetOperations<String, String> zset = redis.opsForZSet();

        // 只把"分拆带"内的划转计入 / only count transfers within the structuring band
        boolean inBand = amount >= BAND_FLOOR && amount < CTR_THRESHOLD;
        if (inBand) {
            zset.add(key, txn.getTransactionId() + "|" + amount, now);
            redis.expire(key, KEY_TTL_HOURS, TimeUnit.HOURS);
        }
        zset.removeRangeByScore(key, 0, windowStart);

        Set<String> members = zset.rangeByScore(key, windowStart, now);
        int count = members == null ? 0 : members.size();
        double sum = 0.0;
        if (members != null) {
            for (String m : members) {
                int bar = m.lastIndexOf('|');
                if (bar >= 0) {
                    try {
                        sum += Double.parseDouble(m.substring(bar + 1));
                    } catch (NumberFormatException ignored) {
                        // malformed member — skip it
                    }
                }
            }
        }

        if (inBand && count >= MIN_COUNT && sum >= CTR_THRESHOLD) {
            double score = Math.min(0.95, 0.55 + 0.1 * (count - MIN_COUNT));
            return AmlSignal.builder()
                    .typology(getName().replace("Typology", ""))
                    .score(score)
                    .explanation(String.format(
                            "Account %s made %d sub-$10k transfers totalling $%,.0f in 24h — "
                                    + "consistent with structuring to evade CTR reporting",
                            txn.getSenderAccount(), count, sum))
                    .build();
        }
        return AmlSignal.builder().typology(getName().replace("Typology", ""))
                .score(0.0).explanation("No structuring pattern").build();
    }
}
