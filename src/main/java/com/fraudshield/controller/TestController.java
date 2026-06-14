package com.fraudshield.controller;

import com.fraudshield.kafka.OrderEventProducer;
import com.fraudshield.model.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/test")
public class TestController {

    private final OrderEventProducer producer;

    public TestController(OrderEventProducer producer) {
        this.producer = producer;
    }

    /**
     * 在后台线程发送5条测试订单，立即返回响应
     * Fires 5 test orders on a background thread so the HTTP response is instant.
     *
     * HTTP线程不等Kafka消费完成 — async processing pattern
     * HTTP thread returns immediately; Kafka consumer processes in the background.
     */
    @GetMapping("/send-orders")
    public ResponseEntity<Map<String, String>> sendOrders() {
        Thread.ofVirtual().start(producer::sendTestOrders);
        return ResponseEntity.ok(Map.of("message", "sending 5 test orders to Kafka"));
    }

    @GetMapping("/send-one")
    public ResponseEntity<Order> sendOne(
            @RequestParam String userId,
            @RequestParam String ip,
            @RequestParam Double amount) {

        Order order = new Order(
                "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                userId,
                amount,
                ip,
                "DEV-MANUAL",
                LocalDateTime.now()
        );
        producer.sendOrder(order);
        return ResponseEntity.ok(order);
    }
}
