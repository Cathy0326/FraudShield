package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskEvent;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.repository.RiskEventRepository;
import com.fraudshield.rule.RiskDetectionEngine;
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

    public OrderEventConsumer(RiskDetectionEngine engine, RiskEventRepository repository) {
        this.engine = engine;
        this.repository = repository;
    }

    /**
     * Kafka消费者入口 — 每条order-events消息触发一次
     * Entry point: called once per message on the order-events topic.
     *
     * 幂等性保证 (Idempotency):
     *   Kafka的"at-least-once"语义下，同一消息可能被投递多次（网络重试、rebalance）。
     *   在保存前先检查orderId是否已存在，避免重复写入。
     *   Kafka at-least-once = same message can arrive more than once.
     *   We check for an existing orderId before saving to stay idempotent.
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
            // 消费者绝不能因业务异常崩溃 — 记录后继续消费下一条
            // Consumer must never crash on a business error — log and move on
            log.error("Risk engine failed for orderId={}: {}", order.getOrderId(), e.getMessage(), e);
            return;
        }

        log.info("Risk result: orderId={}, riskLevel={}, riskScore={}, rules={}",
                result.getOrderId(), result.getRiskLevel(),
                result.getRiskScore(), result.getTriggeredRules());

        // 只持久化HIGH和MEDIUM风险，NORMAL和LOW只记日志节省存储
        // Persist only HIGH/MEDIUM; NORMAL/LOW are log-only to save storage
        if (result.getRiskLevel() == RiskLevel.HIGH || result.getRiskLevel() == RiskLevel.MEDIUM) {
            saveIfAbsent(order, result);
        }
    }

    private void saveIfAbsent(Order order, RiskResult result) {
        // 幂等性检查：已存在则跳过，避免重复写入
        // Idempotency check: skip if this orderId was already persisted
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
        log.info("Saved RiskEvent for {}/score={} order: {}", result.getRiskLevel(), result.getRiskScore(), order.getOrderId());
    }
}
