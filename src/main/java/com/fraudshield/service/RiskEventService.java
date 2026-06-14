package com.fraudshield.service;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.dto.HourlyStatDTO;
import com.fraudshield.dto.RiskEventDTO;
import com.fraudshield.exception.ResourceNotFoundException;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    public void deleteEvent(Long id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("Risk event not found with id: " + id);
        }
        repository.deleteById(id);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
