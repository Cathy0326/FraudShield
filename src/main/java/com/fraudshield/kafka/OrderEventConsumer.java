package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.repository.RiskEventRepository;
import com.fraudshield.rule.RiskDetectionEngine;
import com.fraudshield.service.RiskEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final RiskDetectionEngine engine;
    private final RiskEventRepository repository;
    private final RiskEventService riskEventService;

    public OrderEventConsumer(RiskDetectionEngine engine,
                              RiskEventRepository repository,
                              RiskEventService riskEventService) {
        this.engine           = engine;
        this.repository       = repository;
        this.riskEventService = riskEventService;
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
            // NORMAL/LOW → 只记日志，但增加Redis普通订单计数器
            // NORMAL/LOW → log only, but track in Redis so dashboard can show total volume
            riskEventService.incrementNormalCounter();
        }
    }

    private void saveIfAbsent(Order order, RiskResult result) {
        if (repository.findByOrderId(order.getOrderId()).isPresent()) {
            log.warn("Duplicate message detected for orderId={}, skipping save", order.getOrderId());
            return;
        }

        RiskEvent event = RiskEvent.builder()
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .ipAddress(order.getIpAddress())
                .amount(order.getAmount())
                .riskLevel(result.getRiskLevel().name())
                .riskScore(result.getRiskScore())
                .triggeredRules(String.join(",", result.getTriggeredRules()))
                .explanation(result.getExplanation())
                .build();

        repository.save(event);
        log.info("Saved RiskEvent for {}/score={} order: {}",
                result.getRiskLevel(), result.getRiskScore(), order.getOrderId());
    }
}
