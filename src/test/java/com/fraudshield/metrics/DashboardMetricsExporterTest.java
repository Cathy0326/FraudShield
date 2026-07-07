package com.fraudshield.metrics;

import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardMetricsExporterTest {

    @Mock RiskEventRepository repository;

    private SimpleMeterRegistry registry;
    private DashboardMetricsExporter exporter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        exporter = new DashboardMetricsExporter(repository, registry);
    }

    private RiskEvent event(String status, Double amount, LocalDateTime detectedAt,
                            LocalDateTime reviewedAt, Boolean aiEnhanced) {
        return RiskEvent.builder()
                .orderId("ORD-" + System.identityHashCode(new Object()))
                .riskLevel("HIGH")
                .amount(amount)
                .reviewStatus(status)
                .detectedAt(detectedAt)
                .reviewedAt(reviewedAt)
                .aiEnhanced(aiEnhanced)
                .build();
    }

    private double gauge(String name, String... tags) {
        return registry.get(name).tags(tags).gauge().value();
    }

    @Test
    void queueDepthAndDollars_countPendingAndLegacyNullRows() {
        when(repository.findAll()).thenReturn(List.of(
                event("PENDING_REVIEW", 100.0, null, null, null),
                event(null, 50.0, null, null, null),           // legacy row = pending
                event("APPROVED", 999.0, null, null, null)));  // decided = not at risk

        exporter.export();

        assertThat(gauge("fraudshield.review.queue_depth")).isEqualTo(2.0);
        assertThat(gauge("fraudshield.review.dollars_at_risk")).isEqualTo(150.0);
    }

    @Test
    void decisions_areCountedByOutcome() {
        when(repository.findAll()).thenReturn(List.of(
                event("CONFIRMED_FRAUD", 10.0, null, null, null),
                event("CONFIRMED_FRAUD", 10.0, null, null, null),
                event("FALSE_POSITIVE", 10.0, null, null, null)));

        exporter.export();

        assertThat(gauge("fraudshield.review.decisions_total", "outcome", "CONFIRMED_FRAUD"))
                .isEqualTo(2.0);
        assertThat(gauge("fraudshield.review.decisions_total", "outcome", "FALSE_POSITIVE"))
                .isEqualTo(1.0);
        assertThat(gauge("fraudshield.review.decisions_total", "outcome", "APPROVED"))
                .isEqualTo(0.0);
    }

    @Test
    void avgTimeToDecision_averagesDetectionToReviewMinutes() {
        LocalDateTime detected = LocalDateTime.of(2026, 1, 1, 12, 0);
        when(repository.findAll()).thenReturn(List.of(
                event("APPROVED", 10.0, detected, detected.plusMinutes(10), null),
                event("CONFIRMED_FRAUD", 10.0, detected, detected.plusMinutes(30), null)));

        exporter.export();

        assertThat(gauge("fraudshield.review.avg_time_to_decision_minutes")).isEqualTo(20.0);
    }

    @Test
    void aiEnhancedAndFallback_areCountedSeparately_nullMeansAiNeverRan() {
        when(repository.findAll()).thenReturn(List.of(
                event("PENDING_REVIEW", 10.0, null, null, true),
                event("PENDING_REVIEW", 10.0, null, null, true),
                event("PENDING_REVIEW", 10.0, null, null, false),
                event("PENDING_REVIEW", 10.0, null, null, null))); // HIGH order: AI path skipped

        exporter.export();

        assertThat(gauge("fraudshield.ai.enhanced_total")).isEqualTo(2.0);
        assertThat(gauge("fraudshield.ai.fallback_total")).isEqualTo(1.0);
    }

    @Test
    void emptyTable_allGaugesZero() {
        when(repository.findAll()).thenReturn(List.of());

        exporter.export();

        assertThat(gauge("fraudshield.review.queue_depth")).isEqualTo(0.0);
        assertThat(gauge("fraudshield.review.dollars_at_risk")).isEqualTo(0.0);
        assertThat(gauge("fraudshield.review.avg_time_to_decision_minutes")).isEqualTo(0.0);
    }
}
