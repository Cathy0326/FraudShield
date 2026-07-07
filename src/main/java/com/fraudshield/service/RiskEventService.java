package com.fraudshield.service;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.dto.HourlyStatDTO;
import com.fraudshield.dto.RiskEventDTO;
import com.fraudshield.dto.RulePrecisionDTO;
import com.fraudshield.dto.UserRiskProfileDTO;
import com.fraudshield.exception.ResourceNotFoundException;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class RiskEventService {

    private static final String NORMAL_COUNTER_KEY = "counter:normal_orders";
    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    private final RiskEventRepository repository;
    private final StringRedisTemplate redisTemplate;

    public RiskEventService(RiskEventRepository repository, StringRedisTemplate redisTemplate) {
        this.repository    = repository;
        this.redisTemplate = redisTemplate;
    }

    public List<RiskEventDTO> getRecentEvents(int limit) {
        // Cap at 50 to prevent accidental large reads
        int capped = Math.min(limit, 50);
        return repository.findTop10ByOrderByDetectedAtDesc()
                .stream()
                .limit(capped)
                .map(RiskEventDTO::fromEntity)
                .collect(Collectors.toList());
    }

    public List<RiskEventDTO> getEventsByRiskLevel(String riskLevel) {
        return repository.findByRiskLevelOrderByDetectedAtDesc(riskLevel)
                .stream()
                .map(RiskEventDTO::fromEntity)
                .collect(Collectors.toList());
    }

    public RiskEventDTO getEventByOrderId(String orderId) {
        RiskEvent event = repository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Risk event not found for orderId: " + orderId));
        return RiskEventDTO.fromEntity(event);
    }

    /**
     * 仪表盘统计 — 汇总DB和Redis数据
     * Dashboard stats: aggregates DB counts + Redis counter + hourly trend.
     */
    public DashboardStatsDTO getDashboardStats() {
        long high   = coalesce(repository.countByRiskLevel("HIGH"));
        long medium = coalesce(repository.countByRiskLevel("MEDIUM"));
        long low    = coalesce(repository.countByRiskLevel("LOW"));
        long total  = high + medium + low;

        // 普通订单数从Redis计数器读取（未持久化到DB）
        // Normal orders are counted in Redis — not persisted, so not in DB
        long normal = parseRedisLong(NORMAL_COUNTER_KEY);

        double riskRate = total == 0 ? 0.0 : (high + medium) * 100.0 / total;

        // 规则命中次数：遍历所有记录的triggeredRules字段统计
        // Rule hit counts: parse the triggeredRules CSV field across all stored events
        Map<String, Long> ruleHitCounts = buildRuleHitCounts();

        List<HourlyStatDTO> hourlyTrend = buildHourlyTrend();

        return DashboardStatsDTO.builder()
                .totalOrders(total)
                .highRiskCount(high)
                .mediumRiskCount(medium)
                .lowRiskCount(low)
                .normalCount(normal)
                .riskRate(Math.round(riskRate * 100.0) / 100.0)
                .ruleHitCounts(ruleHitCounts)
                .hourlyTrend(hourlyTrend)
                .build();
    }

    /**
     * NORMAL注文数のRedisカウンターをインクリメント
     * Increment the Redis counter for NORMAL orders (called by OrderEventConsumer).
     */
    public void incrementNormalCounter() {
        redisTemplate.opsForValue().increment(NORMAL_COUNTER_KEY);
    }

    /**
     * 幂等版计数器：Kafka at-least-once语义下同一消息可能被投递多次，
     * 用SETNX标记orderId是否已计数过，只有首次处理才增加计数。
     * 标记24小时后过期 —— 重复投递（重试/rebalance）都发生在分钟级窗口内，
     * 无需永久保留去重标记。
     *
     * Idempotent counter: under Kafka's at-least-once semantics the same message can
     * be delivered more than once. A SETNX marker per orderId ensures the counter only
     * increments on first processing. Markers expire after 24h — redeliveries (retries,
     * rebalances) happen within minutes, so dedup state doesn't need to live forever.
     */
    public void incrementNormalCounterIdempotent(String orderId) {
        Boolean firstTime = redisTemplate.opsForValue().setIfAbsent(
                "processed:normal:" + orderId, "1", Duration.ofHours(24));
        if (Boolean.TRUE.equals(firstTime)) {
            redisTemplate.opsForValue().increment(NORMAL_COUNTER_KEY);
        }
    }

    /**
     * 用户风险画像 — 该用户的历史订单及命中规则，以及与其共享IP的其他账号
     * Per-user risk profile: this user's order/risk history, plus other userIds
     * seen ordering from the same IP(s) — a fraud-ring signal no single-order rule sees.
     */
    public UserRiskProfileDTO getUserRiskProfile(String userId) {
        List<RiskEvent> history = repository.findByUserIdOrderByDetectedAtDesc(userId);

        long high   = history.stream().filter(e -> "HIGH".equals(e.getRiskLevel())).count();
        long medium = history.stream().filter(e -> "MEDIUM".equals(e.getRiskLevel())).count();
        long low    = history.stream().filter(e -> "LOW".equals(e.getRiskLevel())).count();
        double totalAmount = history.stream()
                .map(RiskEvent::getAmount)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();

        Set<String> ips = history.stream()
                .map(RiskEvent::getIpAddress)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<String> linkedUserIds = ips.stream()
                .flatMap(ip -> repository.findByIpAddressOrderByDetectedAtDesc(ip).stream())
                .map(RiskEvent::getUserId)
                .filter(id -> id != null && !id.equals(userId))
                .distinct()
                .collect(Collectors.toList());

        return UserRiskProfileDTO.builder()
                .userId(userId)
                .totalOrders(history.size())
                .highRiskCount(high)
                .mediumRiskCount(medium)
                .lowRiskCount(low)
                .totalAmount(Math.round(totalAmount * 100.0) / 100.0)
                .recentEvents(history.stream().map(RiskEventDTO::fromEntity).collect(Collectors.toList()))
                .linkedUserIds(linkedUserIds)
                .build();
    }

    // 允许的审核决定 / valid terminal review decisions
    private static final Set<String> VALID_DECISIONS =
            Set.of("CONFIRMED_FRAUD", "FALSE_POSITIVE", "APPROVED");

    /**
     * 待审队列 — 检测只是流程的开头，ops人员从这里领取待处理订单
     * Review queue: every flagged event awaiting a human decision, oldest risk first.
     */
    public List<RiskEventDTO> getReviewQueue() {
        return repository.findPendingReview()
                .stream()
                .map(RiskEventDTO::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * 提交审核决定 — PENDING_REVIEW → CONFIRMED_FRAUD / FALSE_POSITIVE / APPROVED（终态）
     * Submit a review decision. Terminal states are immutable: re-reviewing a decided
     * event would corrupt the label data that rule-precision stats are built on.
     *
     * @param reviewer taken from the authenticated principal, never from the request body
     */
    public RiskEventDTO reviewEvent(String orderId, String decision, String reviewer, String notes) {
        if (!VALID_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException(
                    "Invalid review decision: " + decision + ". Must be one of " + VALID_DECISIONS);
        }

        RiskEvent event = repository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Risk event not found for orderId: " + orderId));

        String current = event.getReviewStatus();
        if (current != null && !"PENDING_REVIEW".equals(current)) {
            throw new IllegalStateException(
                    "Order " + orderId + " already reviewed as " + current + " by " + event.getReviewedBy());
        }

        event.setReviewStatus(decision);
        event.setReviewedBy(reviewer);
        event.setReviewedAt(LocalDateTime.now());
        event.setReviewNotes(notes);
        return RiskEventDTO.fromEntity(repository.save(event));
    }

    /**
     * 规则精度统计 — 审核标注的第一个用途：哪条规则误报最多？
     * Rule precision from review labels — the first payoff of the review workflow:
     * which rule generates the most false alarms?
     *
     * <p>精度口径：confirmedFraud / 已审核命中数。APPROVED和FALSE_POSITIVE都算误报，
     * 因为订单最终被放行 —— 规则拦错了。
     * Precision = confirmedFraud / reviewedHits. Both APPROVED and FALSE_POSITIVE count
     * against the rule: the order was ultimately allowed, so the rule flagged wrongly.
     *
     * <p>与buildRuleHitCounts一样是全表遍历 —— demo规模的取舍；规模化时应在审核
     * 提交时增量更新计数（与规则命中计数同一演进路径）。
     * Full-table scan like buildRuleHitCounts — a demo-scale trade-off. At scale these
     * counters would be incremented at review-submission time instead.
     */
    public List<RulePrecisionDTO> getRulePrecision() {
        Map<String, long[]> byRule = new HashMap<>(); // [totalHits, confirmed, falsePos, approved]

        for (RiskEvent e : repository.findAll()) {
            if (e.getTriggeredRules() == null || e.getTriggeredRules().isBlank()) {
                continue;
            }
            String status = e.getReviewStatus();
            for (String raw : e.getTriggeredRules().split(",")) {
                String rule = raw.trim();
                if (rule.isEmpty()) {
                    continue;
                }
                long[] counts = byRule.computeIfAbsent(rule, r -> new long[4]);
                counts[0]++;
                if ("CONFIRMED_FRAUD".equals(status)) {
                    counts[1]++;
                } else if ("FALSE_POSITIVE".equals(status)) {
                    counts[2]++;
                } else if ("APPROVED".equals(status)) {
                    counts[3]++;
                }
            }
        }

        return byRule.entrySet().stream()
                .map(entry -> {
                    long[] c = entry.getValue();
                    long reviewed = c[1] + c[2] + c[3];
                    Double precision = reviewed == 0 ? null
                            : Math.round(c[1] * 10000.0 / reviewed) / 100.0;
                    return RulePrecisionDTO.builder()
                            .rule(entry.getKey())
                            .totalHits(c[0])
                            .reviewedHits(reviewed)
                            .confirmedFraud(c[1])
                            .falsePositive(c[2])
                            .approved(c[3])
                            .precision(precision)
                            .build();
                })
                // 误报最多的排前面 —— 这是ops最需要看到的 / worst offenders first
                .sorted(Comparator.comparingLong(
                        (RulePrecisionDTO d) -> d.getFalsePositive() + d.getApproved()).reversed()
                        .thenComparing(RulePrecisionDTO::getRule))
                .collect(Collectors.toList());
    }

    public void deleteEvent(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Risk event not found with id: " + id);
        }
        repository.deleteById(id);
    }

    // ── Private helpers ──────────────────────────────

    private Map<String, Long> buildRuleHitCounts() {
        // 从所有已存储事件中解析CSV规则字段
        // Parse the CSV triggeredRules field from every stored event
        return repository.findAll().stream()
                .filter(e -> e.getTriggeredRules() != null && !e.getTriggeredRules().isBlank())
                .flatMap(e -> Arrays.stream(e.getTriggeredRules().split(",")))
                .map(String::trim)
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));
    }

    private List<HourlyStatDTO> buildHourlyTrend() {
        // 最近24小时，每小时一个桶
        // One bucket per hour for the last 24 hours
        List<HourlyStatDTO> trend = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 23; i >= 0; i--) {
            LocalDateTime start = now.minusHours(i).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime end   = start.plusHours(1);

            List<RiskEvent> events = repository.findByDetectedAtBetween(start, end);
            long riskCount = events.stream()
                    .filter(e -> "HIGH".equals(e.getRiskLevel()) || "MEDIUM".equals(e.getRiskLevel()))
                    .count();

            trend.add(HourlyStatDTO.builder()
                    .hour(start.format(HOUR_FMT))
                    .orderCount((long) events.size())
                    .riskCount(riskCount)
                    .build());
        }
        return trend;
    }

    private long coalesce(Long value) {
        return value == null ? 0L : value;
    }

    private long parseRedisLong(String key) {
        String val = redisTemplate.opsForValue().get(key);
        if (val == null) {
            return 0L;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
