package com.fraudshield.kafka;

import com.fraudshield.model.AiAnalysis;
import com.fraudshield.model.Order;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.repository.RiskEventRepository;
import com.fraudshield.rule.RiskDetectionEngine;
import com.fraudshield.service.AzureOpenAIService;
import com.fraudshield.service.RiskEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final RiskDetectionEngine engine;
    private final RiskEventRepository repository;
    private final RiskEventService riskEventService;
    private final AzureOpenAIService aiService;

    public OrderEventConsumer(RiskDetectionEngine engine,
                              RiskEventRepository repository,
                              RiskEventService riskEventService,
                              AzureOpenAIService aiService) {
        this.engine           = engine;
        this.repository       = repository;
        this.riskEventService = riskEventService;
        this.aiService        = aiService;
    }

    /**
     * Kafka消费者入口 — 每条order-events消息触发一次
     * Entry point: called once per message on the order-events topic.
     *
     * 幂等性保证 (Idempotency):
     *   Kafka的"at-least-once"语义下，同一消息可能被投递多次（网络重试、rebalance）。
     *   在保存前先检查orderId是否已存在，避免重复写入。
     */
    @KafkaListener(topics = "order-events", groupId = "fraudshield-group",
                   containerFactory = "kafkaListenerContainerFactory")
    public void consume(Order order) {
        log.info("Received order: orderId={}, userId={}, amount={}, ip={}",
                order.getOrderId(), order.getUserId(), order.getAmount(), order.getIpAddress());

        RiskResult result;
        try {
            result = engine.evaluate(order);
        } catch (Exception e) {
            log.error("Risk engine failed for orderId={}: {}", order.getOrderId(), e.getMessage(), e);
            return;
        }

        log.info("Risk result: orderId={}, riskLevel={}, riskScore={}, rules={}",
                result.getOrderId(), result.getRiskLevel(),
                result.getRiskScore(), result.getTriggeredRules());

        if (result.getRiskLevel() == RiskLevel.HIGH || result.getRiskLevel() == RiskLevel.MEDIUM) {
            saveIfAbsent(order, result);
        } else {
            // NORMAL/LOW → 只记日志，但增加Redis普通订单计数器（按orderId去重，防止重复投递重复计数）
            // NORMAL/LOW → log only, but track in Redis so dashboard can show total volume.
            // Deduplicated by orderId so at-least-once redelivery doesn't double-count.
            riskEventService.incrementNormalCounterIdempotent(order.getOrderId());
        }
    }

    private void saveIfAbsent(Order order, RiskResult result) {
        if (repository.findByOrderId(order.getOrderId()).isPresent()) {
            log.warn("Duplicate message detected for orderId={}, skipping save", order.getOrderId());
            return;
        }

        RiskEvent.RiskEventBuilder builder = RiskEvent.builder()
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .ipAddress(order.getIpAddress())
                .deviceId(order.getDeviceId())
                .shippingAddress(order.getShippingAddress())
                .billingAddress(order.getBillingAddress())
                .amount(order.getAmount())
                .riskLevel(result.getRiskLevel().name())
                .riskScore(result.getRiskScore())
                .triggeredRules(String.join(",", result.getTriggeredRules()))
                .explanation(result.getExplanation());

        // 只对MEDIUM风险订单调用AI（HIGH已确定，无需额外分析；调用有成本）
        // Only call AI for MEDIUM — HIGH is certain (cost saving), NORMAL/LOW never reach here
        if (result.getRiskLevel() == RiskLevel.MEDIUM) {
            AiAnalysis ai = aiService.analyze(order, result);
            builder.aiRiskLevel(ai.getAiRiskLevel())
                   .aiConfidence(ai.getConfidence())
                   .aiReasoning(ai.getReasoning())
                   .aiRecommendation(ai.getRecommendation())
                   .aiKeyFactors(ai.getKeyFactors() == null ? null
                           : String.join(",", ai.getKeyFactors()))
                   .aiEnhanced(ai.isAiEnhanced());
            log.info("AI analysis for orderId={}: level={}, confidence={}, enhanced={}",
                    order.getOrderId(), ai.getAiRiskLevel(), ai.getConfidence(), ai.isAiEnhanced());
        }

        try {
            repository.save(builder.build());
        } catch (DataIntegrityViolationException e) {
            // check-then-insert竞态的兜底：并发重复投递同时通过了findByOrderId检查，
            // DB唯一约束拦下第二次插入 —— 这是预期的幂等行为，不是错误
            // Backstop for the check-then-insert race: a concurrent redelivery passed the
            // findByOrderId check too; the DB unique constraint rejects the second insert.
            // Expected idempotent behavior, not an error.
            log.warn("Concurrent duplicate insert blocked by unique constraint for orderId={}",
                    order.getOrderId());
            return;
        }
        log.info("Saved RiskEvent for {}/score={} order: {}",
                result.getRiskLevel(), result.getRiskScore(), order.getOrderId());
    }
}
