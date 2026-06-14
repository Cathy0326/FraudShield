package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    @Value("${fraudshield.kafka.topic.order-events}")
    private String topic;

    private final KafkaTemplate<String, Order> kafkaTemplate;

    public OrderEventProducer(KafkaTemplate<String, Order> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendOrder(Order order) {
        // key = orderId 保证同一订单始终路由到同一partition（保序）
        // key = orderId ensures the same order always goes to the same partition (ordering guarantee)
        kafkaTemplate.send(topic, order.getOrderId(), order);
        log.info("Sent order {} to topic '{}'", order.getOrderId(), topic);
    }

    /**
     * 发送5条预设测试订单，覆盖所有规则场景
     * Sends 5 preset orders that exercise every rule in the engine.
     */
    public void sendTestOrders() {
        List<Order> testOrders = List.of(
            // 1. HighAmountNewUserRule → MEDIUM (USER-TEST-001 created 2h ago, amount 150 > 100)
            new Order(uid(), "USER-TEST-001", 150.0, "192.168.1.1", "DEV-1", LocalDateTime.now()),
            // 2. BlacklistRule (userId) → HIGH
            new Order(uid(), "USER-BAD-001",  50.0,  "1.2.3.4",    "DEV-2", LocalDateTime.now()),
            // 3. AbnormalAmountRule → MEDIUM (USER-TEST-002 avg=50, amount 200 > 50*3)
            new Order(uid(), "USER-TEST-002", 200.0, "5.5.5.5",    "DEV-3", LocalDateTime.now()),
            // 4. BlacklistRule (ip) → HIGH (ip 10.0.0.1 is blacklisted)
            new Order(uid(), "USER-NORMAL",   30.0,  "10.0.0.1",   "DEV-4", LocalDateTime.now()),
            // 5. NORMAL — no rule triggered
            new Order(uid(), "USER-NORMAL-2", 20.0,  "9.9.9.9",    "DEV-5", LocalDateTime.now())
        );

        for (Order order : testOrders) {
            sendOrder(order);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String uid() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
