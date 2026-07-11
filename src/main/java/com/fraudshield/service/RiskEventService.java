package com.fraudshield.service;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.dto.DisputeEvidenceDTO;
import com.fraudshield.dto.FinancialImpactDTO;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class RiskEventService {

    private static final String NORMAL_COUNTER_KEY = "counter:normal_orders";
    private static final DateTimeFormatter HOUR_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");

    private final RiskEventRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final AuditChainService auditChain;

    public RiskEventService(RiskEventRepository repository, StringRedisTemplate redisTemplate,
                            AuditChainService auditChain) {
        this.repository    = repository;
        this.redisTemplate = redisTemplate;
        this.auditChain    = auditChain;
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

    /**
     * 日期范围查询 — Reports页面的数据源。此前前端的日期选择器是摆设：
     * 后端没有接受日期参数的接口，"Generate Report"实际只拉最近10条。
     * Date-range query backing the Reports page. Until this endpoint existed the
     * frontend date pickers were decorative - "Generate Report" silently fetched
     * the 10 most recent events regardless of the chosen dates.
     *
     * <p>区间口径：[from 00:00, to次日00:00)，即两端日期都完整包含。
     * Range is [from at 00:00, day-after-to at 00:00) — both endpoint days inclusive.
     */
    public List<RiskEventDTO> getEventsByDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("Both 'from' and 'to' dates are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "'from' date " + from + " must not be after 'to' date " + to);
        }
        return repository.findByDetectedAtBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay())
                .stream()
                .sorted(Comparator.comparing(RiskEvent::getDetectedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
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

        // 一次取14天窗口，趋势/基线/周同比全部在内存分桶 —— 不再每小时查一次DB
        // One 14-day fetch; trend, baseline, and week-over-week are all bucketed
        // in memory - no more one-query-per-hour.
        LocalDateTime now = LocalDateTime.now();
        List<RiskEvent> window = repository.findByDetectedAtBetween(now.minusDays(14), now);

        List<HourlyStatDTO> hourlyTrend = buildHourlyTrend(window, now);

        long highMedLast7d = countFlagged(window, now.minusDays(7), now, "HIGH");
        long highMedPrev7d = countFlagged(window, now.minusDays(14), now.minusDays(7), "HIGH");
        long medLast7d = countFlagged(window, now.minusDays(7), now, "MEDIUM");
        long medPrev7d = countFlagged(window, now.minusDays(14), now.minusDays(7), "MEDIUM");

        return DashboardStatsDTO.builder()
                .totalOrders(total)
                .highRiskCount(high)
                .mediumRiskCount(medium)
                .lowRiskCount(low)
                .normalCount(normal)
                .riskRate(Math.round(riskRate * 100.0) / 100.0)
                .ruleHitCounts(ruleHitCounts)
                .hourlyTrend(hourlyTrend)
                .highRiskWowDelta(highMedLast7d - highMedPrev7d)
                .mediumRiskWowDelta(medLast7d - medPrev7d)
                .build();
    }

    private long countFlagged(List<RiskEvent> events, LocalDateTime from, LocalDateTime to,
                              String level) {
        return events.stream()
                .filter(e -> e.getDetectedAt() != null
                        && !e.getDetectedAt().isBefore(from) && e.getDetectedAt().isBefore(to)
                        && level.equals(e.getRiskLevel()))
                .count();
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
        Set<String> devices = history.stream()
                .map(RiskEvent::getDeviceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 共享IP或共享设备都算关联账号 —— 设备信号更强（共享NAT会让IP误伤，设备不会）
        // Linked = shared IP OR shared device; the device signal is stronger
        // (shared NAT causes IP false positives, devices don't).
        List<String> linkedUserIds = Stream.concat(
                        ips.stream().flatMap(ip ->
                                repository.findByIpAddressOrderByDetectedAtDesc(ip).stream()),
                        devices.stream().flatMap(dev ->
                                repository.findByDeviceIdOrderByDetectedAtDesc(dev).stream()))
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
        // 优先级 = 风险分 × 金额：$2000的HIGH单和$15的MEDIUM单不该同等排队。
        // 真实审核员按"期望损失"处理 —— 这正是expected loss的最简代理。
        // Priority = riskScore x amount: a $2,000 HIGH order and a $15 MEDIUM one
        // should not queue equally. Reviewers triage by expected loss, and
        // score x amount is its simplest proxy.
        return repository.findPendingReview()
                .stream()
                .sorted(Comparator.comparingDouble(RiskEventService::expectedLoss).reversed())
                .map(RiskEventDTO::fromEntity)
                .collect(Collectors.toList());
    }

    private static double expectedLoss(RiskEvent e) {
        double score = e.getRiskScore() == null ? 0.0 : e.getRiskScore();
        double amount = e.getAmount() == null ? 0.0 : e.getAmount();
        return score * amount;
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
        RiskEventDTO saved = RiskEventDTO.fromEntity(repository.save(event));

        // 每个审核决定追加为审计链上的一环（防篡改，见AuditChainService）
        // Every decision is appended to the tamper-evident audit chain
        auditChain.append(orderId, decision, reviewer);
        return saved;
    }

    /**
     * 争议证据包 — 把系统已有的数据组装成一份可提交的chargeback证据
     * Dispute evidence package: assembles data the system already holds into
     * one submittable chargeback exhibit. Zero new detection logic — the value
     * is in the packaging, and it gives the audit chain its second use case
     * (attesting to card networks that the decision record is unaltered).
     *
     * @param requestedBy taken from the authenticated principal — the evidence
     *                    header records who generated the package
     */
    public DisputeEvidenceDTO getDisputeEvidence(String orderId, String requestedBy) {
        RiskEvent event = repository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Risk event not found for orderId: " + orderId));

        Map<String, Object> chainStatus = auditChain.verifyChain();

        return DisputeEvidenceDTO.builder()
                .generatedAt(LocalDateTime.now())
                .generatedBy(requestedBy)
                .event(RiskEventDTO.fromEntity(event))
                .auditTrail(auditChain.getRecordsForOrder(orderId))
                .chainValid(Boolean.TRUE.equals(chainStatus.get("valid")))
                .chainRecordCount(((Number) chainStatus.getOrDefault("records", 0)).longValue())
                .build();
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

    /**
     * 财务影响汇总 — 用美元回答"风控值不值"：拦截额 vs 误杀额
     * Financial impact: answers "is the fraud program worth it" in dollars -
     * intercepted losses vs wrongly blocked revenue. Demo-scale full scan,
     * same documented trade-off as the other aggregations.
     */
    public FinancialImpactDTO getFinancialImpact() {
        double intercepted = 0;
        double falseKill = 0;
        double released = 0;
        long nIntercepted = 0;
        long nFalseKill = 0;
        long nReleased = 0;

        for (RiskEvent e : repository.findAll()) {
            double amount = e.getAmount() == null ? 0.0 : e.getAmount();
            String status = e.getReviewStatus();
            if ("CONFIRMED_FRAUD".equals(status)) {
                intercepted += amount;
                nIntercepted++;
            } else if ("FALSE_POSITIVE".equals(status)) {
                falseKill += amount;
                nFalseKill++;
            } else if ("APPROVED".equals(status)) {
                released += amount;
                nReleased++;
            }
        }

        return FinancialImpactDTO.builder()
                .interceptedAmount(round2(intercepted))
                .falsePositiveAmount(round2(falseKill))
                .approvedAmount(round2(released))
                .interceptedCount(nIntercepted)
                .falsePositiveCount(nFalseKill)
                .approvedCount(nReleased)
                .interceptToFalseKillRatio(falseKill == 0 ? null : round2(intercepted / falseKill))
                .build();
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
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

    /**
     * 近24小时趋势 + 每小时段的7日基线（均值±σ）
     * Last-24h trend plus a per-hour-slot 7-day baseline (mean ± σ).
     *
     * <p>基线口径：对每个小时桶，取**过去7天同一钟点**的风险单数样本算均值和标准差。
     * 按钟点对齐（而不是简单的滚动均值）是为了吸收日内周期 —— 凌晨3点和晚8点的
     * "正常水平"本来就不同，混在一起的基线会在低谷虚报、在高峰漏报。
     * The baseline for each hourly bucket is the mean/std-dev of risk counts at the
     * SAME hour of day over the prior 7 days. Aligning by hour-of-day (rather than a
     * rolling mean) absorbs the intraday cycle - 3 AM and 8 PM have different
     * "normal", and a mixed baseline would false-alarm in the trough and miss in
     * the peak.
     */
    private List<HourlyStatDTO> buildHourlyTrend(List<RiskEvent> window, LocalDateTime now) {
        // 每小时桶的风险单数，key为整点 / risk counts per hour bucket, keyed by truncated hour
        Map<LocalDateTime, long[]> buckets = new HashMap<>(); // [orderCount, riskCount]
        for (RiskEvent e : window) {
            if (e.getDetectedAt() == null) {
                continue;
            }
            LocalDateTime slot = e.getDetectedAt().withMinute(0).withSecond(0).withNano(0);
            long[] counts = buckets.computeIfAbsent(slot, k -> new long[2]);
            counts[0]++;
            if ("HIGH".equals(e.getRiskLevel()) || "MEDIUM".equals(e.getRiskLevel())) {
                counts[1]++;
            }
        }

        List<HourlyStatDTO> trend = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            LocalDateTime start = now.minusHours(i).withMinute(0).withSecond(0).withNano(0);
            long[] counts = buckets.getOrDefault(start, new long[2]);

            // 过去7天同一钟点的样本 / same-hour samples from the prior 7 days
            double sum = 0;
            double sumSq = 0;
            for (int d = 1; d <= 7; d++) {
                long risk = buckets.getOrDefault(start.minusDays(d), new long[2])[1];
                sum += risk;
                sumSq += (double) risk * risk;
            }
            double mean = sum / 7.0;
            double sigma = Math.sqrt(Math.max(0.0, sumSq / 7.0 - mean * mean));

            trend.add(HourlyStatDTO.builder()
                    .hour(start.format(HOUR_FMT))
                    .orderCount(counts[0])
                    .riskCount(counts[1])
                    .baselineRisk(Math.round(mean * 100.0) / 100.0)
                    .baselineSigma(Math.round(sigma * 100.0) / 100.0)
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
