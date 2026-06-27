package com.fraudshield.metrics;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.service.RiskEventService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically republishes {@link RiskEventService#getDashboardStats()} as
 * Micrometer gauges, so Prometheus's own scrape history turns the existing
 * DB-backed dashboard stats into a real time series — trend-over-time
 * panels in Grafana (fraud rate drifting up, which rule is firing most this
 * week, etc.) without duplicating Prometheus's TSDB by hand.
 *
 * <p>The data was always there (DashboardStatsDTO already aggregates it for
 * the REST dashboard) — this just exposes the same numbers on a second,
 * time-series-shaped door so Grafana can chart them, instead of only ever
 * showing "right now."
 */
@Component
public class DashboardMetricsExporter {

    private final RiskEventService riskEventService;
    private final MeterRegistry meterRegistry;

    private final Map<String, AtomicLong> riskLevelGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> ruleHitGauges = new ConcurrentHashMap<>();
    private final AtomicLong totalOrdersGauge = new AtomicLong();
    private final AtomicReference<Double> riskRateGauge = new AtomicReference<>(0.0);

    public DashboardMetricsExporter(RiskEventService riskEventService, MeterRegistry meterRegistry) {
        this.riskEventService = riskEventService;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("fraudshield.dashboard.total_orders", totalOrdersGauge);
        meterRegistry.gauge("fraudshield.dashboard.risk_rate_percent", riskRateGauge, AtomicReference::get);
    }

    @Scheduled(fixedRate = 15_000)
    public void export() {
        DashboardStatsDTO stats = riskEventService.getDashboardStats();

        totalOrdersGauge.set(orZero(stats.getTotalOrders()));
        riskRateGauge.set(stats.getRiskRate() == null ? 0.0 : stats.getRiskRate());

        updateLevelGauge("HIGH", orZero(stats.getHighRiskCount()));
        updateLevelGauge("MEDIUM", orZero(stats.getMediumRiskCount()));
        updateLevelGauge("LOW", orZero(stats.getLowRiskCount()));
        updateLevelGauge("NORMAL", orZero(stats.getNormalCount()));

        if (stats.getRuleHitCounts() != null) {
            stats.getRuleHitCounts().forEach(this::updateRuleHitGauge);
        }
    }

    private void updateLevelGauge(String riskLevel, long count) {
        riskLevelGauges.computeIfAbsent(riskLevel, level -> {
            AtomicLong holder = new AtomicLong();
            meterRegistry.gauge("fraudshield.dashboard.risk_level_count", List.of(Tag.of("riskLevel", level)), holder);
            return holder;
        }).set(count);
    }

    private void updateRuleHitGauge(String ruleName, long count) {
        ruleHitGauges.computeIfAbsent(ruleName, rule -> {
            AtomicLong holder = new AtomicLong();
            meterRegistry.gauge("fraudshield.dashboard.rule_hit_count", List.of(Tag.of("rule", rule)), holder);
            return holder;
        }).set(count);
    }

    private long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
