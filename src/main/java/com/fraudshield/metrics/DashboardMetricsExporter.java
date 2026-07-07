package com.fraudshield.metrics;

import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 决策过程指标导出器 — Grafana的"决策质量"视角
 * Decision-process metrics exporter: the "decision quality" view in Grafana.
 *
 * <p>设计原则："每个指标只有一个owner"。业务快照（风险分布、规则命中数）的
 * owner是应用/DB，由React dashboard展示 —— 早期版本把它们复制成gauge造成了
 * 双重owner，两边数字可能不一致，已移除。这里只导出**只有作为时间序列才有
 * 意义、且React不展示**的决策过程指标：
 * <ul>
 *   <li>待审队列深度与美元敞口 —— 积压是涨是消？</li>
 *   <li>平均决策时长 —— 审核SLA</li>
 *   <li>按结论分类的累计决定数 —— 标注产出速率</li>
 *   <li>AI增强 vs 降级次数 —— LLM二次分析的实际可用率</li>
 * </ul>
 *
 * <p>Design principle: one owner per metric. Business snapshots (risk mix, rule
 * hit counts) are owned by the app/DB and shown in the React dashboard — an
 * earlier version duplicated them as gauges, creating two owners whose numbers
 * could disagree; those are removed. This exporter publishes only decision-process
 * metrics that are meaningful specifically as time series and are NOT shown in
 * React: review-queue depth and dollar exposure, average time-to-decision,
 * cumulative decisions by outcome, and AI-enhanced vs fallback counts.
 *
 * <p>Prometheus's own scrape history supplies the time dimension — no custom
 * storage. Full-table scan each cycle is the documented demo-scale trade-off,
 * same as the dashboard-stats path.
 */
@Component
public class DashboardMetricsExporter {

    private static final List<String> OUTCOMES =
            List.of("CONFIRMED_FRAUD", "FALSE_POSITIVE", "APPROVED");

    private final RiskEventRepository repository;
    private final MeterRegistry meterRegistry;

    private final AtomicLong queueDepthGauge = new AtomicLong();
    private final AtomicReference<Double> dollarsAtRiskGauge = new AtomicReference<>(0.0);
    private final AtomicReference<Double> avgTimeToDecisionGauge = new AtomicReference<>(0.0);
    private final AtomicLong aiEnhancedGauge = new AtomicLong();
    private final AtomicLong aiFallbackGauge = new AtomicLong();
    private final Map<String, AtomicLong> decisionGauges = new ConcurrentHashMap<>();

    public DashboardMetricsExporter(RiskEventRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("fraudshield.review.queue_depth", queueDepthGauge);
        meterRegistry.gauge("fraudshield.review.dollars_at_risk",
                dollarsAtRiskGauge, AtomicReference::get);
        meterRegistry.gauge("fraudshield.review.avg_time_to_decision_minutes",
                avgTimeToDecisionGauge, AtomicReference::get);
        meterRegistry.gauge("fraudshield.ai.enhanced", aiEnhancedGauge);
        meterRegistry.gauge("fraudshield.ai.fallback", aiFallbackGauge);
    }

    @Scheduled(fixedRate = 15_000)
    public void export() {
        List<RiskEvent> events = repository.findAll();

        long queueDepth = 0;
        double dollarsAtRisk = 0.0;
        double decisionMinutesSum = 0.0;
        long decidedWithTimes = 0;
        long aiEnhanced = 0;
        long aiFallback = 0;
        Map<String, Long> decisions = new HashMap<>();

        for (RiskEvent e : events) {
            String status = e.getReviewStatus();
            boolean pending = status == null || "PENDING_REVIEW".equals(status);
            if (pending) {
                queueDepth++;
                dollarsAtRisk += e.getAmount() == null ? 0.0 : e.getAmount();
            } else {
                decisions.merge(status, 1L, Long::sum);
                if (e.getDetectedAt() != null && e.getReviewedAt() != null) {
                    decisionMinutesSum += Duration.between(
                            e.getDetectedAt(), e.getReviewedAt()).toSeconds() / 60.0;
                    decidedWithTimes++;
                }
            }
            // aiEnhanced非null说明该订单走过AI分析路径 / non-null = the AI path ran
            if (Boolean.TRUE.equals(e.getAiEnhanced())) {
                aiEnhanced++;
            } else if (Boolean.FALSE.equals(e.getAiEnhanced())) {
                aiFallback++;
            }
        }

        queueDepthGauge.set(queueDepth);
        dollarsAtRiskGauge.set(Math.round(dollarsAtRisk * 100.0) / 100.0);
        avgTimeToDecisionGauge.set(decidedWithTimes == 0 ? 0.0
                : Math.round(decisionMinutesSum / decidedWithTimes * 100.0) / 100.0);
        aiEnhancedGauge.set(aiEnhanced);
        aiFallbackGauge.set(aiFallback);
        for (String outcome : OUTCOMES) {
            updateDecisionGauge(outcome, decisions.getOrDefault(outcome, 0L));
        }
    }

    private void updateDecisionGauge(String outcome, long count) {
        decisionGauges.computeIfAbsent(outcome, o -> {
            AtomicLong holder = new AtomicLong();
            meterRegistry.gauge("fraudshield.review.decisions",
                    List.of(Tag.of("outcome", o)), holder);
            return holder;
        }).set(count);
    }
}
