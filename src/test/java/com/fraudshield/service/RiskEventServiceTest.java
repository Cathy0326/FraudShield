package com.fraudshield.service;

import com.fraudshield.dto.DashboardStatsDTO;
import com.fraudshield.dto.RiskEventDTO;
import com.fraudshield.exception.ResourceNotFoundException;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskEventServiceTest {

    @Mock RiskEventRepository repository;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private RiskEventService service;

    private RiskEvent event1, event2, event3;

    @BeforeEach
    void setUp() {
        service = new RiskEventService(repository, redisTemplate);

        LocalDateTime now = LocalDateTime.now();
        event1 = buildEvent(1L, "ORD-001", "HIGH",   1.0,  "BlacklistRule",         now);
        event2 = buildEvent(2L, "ORD-002", "MEDIUM",  0.6,  "HighAmountNewUserRule", now.minusHours(1));
        event3 = buildEvent(3L, "ORD-003", "HIGH",   0.9,  "FrequentIpRule",        now.minusHours(2));
    }

    private RiskEvent buildEvent(Long id, String orderId, String level,
                                  double score, String rules, LocalDateTime at) {
        return RiskEvent.builder()
                .id(id).orderId(orderId).userId("USER-1").ipAddress("1.2.3.4")
                .amount(100.0).riskLevel(level).riskScore(score)
                .triggeredRules(rules).explanation("test").detectedAt(at)
                .build();
    }

    @Test
    void getRecentEvents_returnsConvertedDTOs() {
        when(repository.findTop10ByOrderByDetectedAtDesc()).thenReturn(List.of(event1, event2, event3));

        List<RiskEventDTO> result = service.getRecentEvents(10);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getOrderId()).isEqualTo("ORD-001");
        // Verify CSV → List conversion happened
        assertThat(result.get(0).getTriggeredRules()).isInstanceOf(List.class);
        assertThat(result.get(0).getTriggeredRules()).containsExactly("BlacklistRule");
    }

    @Test
    void getEventsByRiskLevel_filtersCorrectly() {
        when(repository.findByRiskLevelOrderByDetectedAtDesc("HIGH"))
                .thenReturn(List.of(event1, event3));

        List<RiskEventDTO> result = service.getEventsByRiskLevel("HIGH");

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> "HIGH".equals(e.getRiskLevel()));
    }

    @Test
    void getDashboardStats_aggregatesCorrectly() {
        when(repository.countByRiskLevel("HIGH")).thenReturn(2L);
        when(repository.countByRiskLevel("MEDIUM")).thenReturn(1L);
        when(repository.countByRiskLevel("LOW")).thenReturn(0L);
        when(repository.findAll()).thenReturn(List.of(event1, event2, event3));
        when(repository.findByDetectedAtBetween(any(), any())).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("counter:normal_orders")).thenReturn("5");

        DashboardStatsDTO stats = service.getDashboardStats();

        assertThat(stats.getTotalOrders()).isEqualTo(3L);   // 2 HIGH + 1 MEDIUM
        assertThat(stats.getNormalCount()).isEqualTo(5L);
        assertThat(stats.getRuleHitCounts()).containsEntry("BlacklistRule", 1L);
        assertThat(stats.getRuleHitCounts()).containsEntry("HighAmountNewUserRule", 1L);
        assertThat(stats.getRuleHitCounts()).containsEntry("FrequentIpRule", 1L);
    }

    @Test
    void getEventByOrderId_found_returnsDTO() {
        when(repository.findByOrderId("ORD-001")).thenReturn(Optional.of(event1));

        RiskEventDTO dto = service.getEventByOrderId("ORD-001");

        assertThat(dto.getOrderId()).isEqualTo("ORD-001");
    }

    @Test
    void getEventByOrderId_notFound_throwsResourceNotFoundException() {
        when(repository.findByOrderId("NONEXISTENT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEventByOrderId("NONEXISTENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("NONEXISTENT");
    }

    @Test
    void incrementNormalCounter_callsRedisIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.incrementNormalCounter();

        verify(valueOps).increment("counter:normal_orders");
    }
}
