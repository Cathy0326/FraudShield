package com.fraudshield.kafka;

import com.fraudshield.model.AiAnalysis;
import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.repository.RiskEventRepository;
import com.fraudshield.rule.RiskDetectionEngine;
import com.fraudshield.service.AzureOpenAIService;
import com.fraudshield.service.RiskEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fraudshield.model.RiskEvent;

@ExtendWith(MockitoExtension.class)
class OrderEventConsumerTest {

    @Mock RiskDetectionEngine engine;
    @Mock RiskEventRepository repository;
    @Mock RiskEventService riskEventService;
    @Mock AzureOpenAIService aiService;

    private OrderEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderEventConsumer(engine, repository, riskEventService, aiService);
    }

    private Order order(String orderId) {
        return new Order(orderId, "USER-1", 100.0, "1.2.3.4", "DEV-1", LocalDateTime.now());
    }

    private RiskResult result(String orderId, RiskLevel level, double score) {
        return RiskResult.builder()
                .orderId(orderId)
                .riskLevel(level)
                .riskScore(score)
                .triggeredRules(level == RiskLevel.NORMAL ? List.of() : List.of("TestRule"))
                .explanation("test")
                .build();
    }

    @Test
    void highRiskOrder_savesToRepository() {
        Order order = order("ORD-HIGH");
        when(engine.evaluate(order)).thenReturn(result("ORD-HIGH", RiskLevel.HIGH, 1.0));
        when(repository.findByOrderId("ORD-HIGH")).thenReturn(Optional.empty());

        consumer.consume(order);

        verify(repository).save(any());
    }

    @Test
    void mediumRiskOrder_savesToRepository() {
        Order order = order("ORD-MED");
        when(engine.evaluate(order)).thenReturn(result("ORD-MED", RiskLevel.MEDIUM, 0.6));
        when(repository.findByOrderId("ORD-MED")).thenReturn(Optional.empty());
        when(aiService.analyze(any(), any())).thenReturn(
                AiAnalysis.builder().aiRiskLevel("MEDIUM").confidence(0.7)
                        .reasoning("test").recommendation("manual_review")
                        .keyFactors(List.of("factor1")).aiEnhanced(true).build());

        consumer.consume(order);

        verify(repository).save(any());
    }

    @Test
    void normalRiskOrder_doesNotSave() {
        Order order = order("ORD-NORM");
        when(engine.evaluate(order)).thenReturn(result("ORD-NORM", RiskLevel.NORMAL, 0.0));

        consumer.consume(order);

        verify(repository, never()).save(any());
    }

    @Test
    void lowRiskOrder_doesNotSave() {
        Order order = order("ORD-LOW");
        when(engine.evaluate(order)).thenReturn(result("ORD-LOW", RiskLevel.LOW, 0.2));

        consumer.consume(order);

        verify(repository, never()).save(any());
    }

    @Test
    void engineThrowsException_consumerDoesNotRethrow() {
        // 消费者必须捕获异常，绝不让Kafka线程崩溃
        // Consumer must swallow exceptions — a crash would stall the partition
        Order order = order("ORD-ERR");
        when(engine.evaluate(any())).thenThrow(new RuntimeException("redis down"));

        consumer.consume(order);

        verify(repository, never()).save(any());
    }

    @Test
    void duplicateHighRiskOrder_skipsSecondSave() {
        // 幂等性：同一orderId已存在时跳过保存
        // Idempotency: duplicate message for the same orderId must not double-write
        Order order = order("ORD-DUP");
        when(engine.evaluate(order)).thenReturn(result("ORD-DUP", RiskLevel.HIGH, 1.0));
        when(repository.findByOrderId("ORD-DUP")).thenReturn(Optional.of(mock(RiskEvent.class)));

        consumer.consume(order);

        verify(repository, never()).save(any());
    }

    // ── Test 7: MEDIUM订单 → AI分析被调用且结果写入Event ─────────────────────
    // MEDIUM orders must trigger AI analysis and persist the AI fields
    @Test
    void mediumRiskOrder_callsAiAndPersistsAiFields() {
        Order order = order("ORD-MED-AI");
        when(engine.evaluate(order)).thenReturn(result("ORD-MED-AI", RiskLevel.MEDIUM, 0.65));
        when(repository.findByOrderId("ORD-MED-AI")).thenReturn(Optional.empty());
        AiAnalysis ai = AiAnalysis.builder()
                .aiRiskLevel("HIGH").confidence(0.85)
                .reasoning("Very suspicious").recommendation("block")
                .keyFactors(List.of("amount_spike", "new_ip")).aiEnhanced(true).build();
        when(aiService.analyze(any(), any())).thenReturn(ai);

        consumer.consume(order);

        ArgumentCaptor<RiskEvent> captor = ArgumentCaptor.forClass(RiskEvent.class);
        verify(repository).save(captor.capture());
        RiskEvent saved = captor.getValue();
        assertThat(saved.getAiRiskLevel()).isEqualTo("HIGH");
        assertThat(saved.getAiConfidence()).isEqualTo(0.85);
        assertThat(saved.getAiEnhanced()).isTrue();
        assertThat(saved.getAiKeyFactors()).contains("amount_spike");
    }

    // ── Test 8: HIGH订单 → AI分析不被调用 ────────────────────────────────────
    // HIGH-risk orders must NOT call AI (cost saving — certainty is already high)
    @Test
    void highRiskOrder_doesNotCallAi() {
        Order order = order("ORD-HIGH-NO-AI");
        when(engine.evaluate(order)).thenReturn(result("ORD-HIGH-NO-AI", RiskLevel.HIGH, 1.0));
        when(repository.findByOrderId("ORD-HIGH-NO-AI")).thenReturn(Optional.empty());

        consumer.consume(order);

        verify(aiService, never()).analyze(any(), any());
    }
}
