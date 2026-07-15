package com.fraudshield.controller;

import com.fraudshield.kafka.OrderEventProducer;
import com.fraudshield.model.Order;
import com.fraudshield.service.HistoricalSeedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/test")
public class TestController {

    private final OrderEventProducer producer;
    private final HistoricalSeedService historicalSeedService;

    public TestController(OrderEventProducer producer, HistoricalSeedService historicalSeedService) {
        this.producer = producer;
        this.historicalSeedService = historicalSeedService;
    }

    /**
     * 回填过去N天的合成风险事件，让时间范围/趋势/热力图有历史可展示（开发/演示用）。
     * Backfill N days of past-dated synthetic events so the history-oriented views have
     * something to show. Dev/demo only. Re-seeding clears the previous synthetic batch.
     */
    @GetMapping("/seed-history")
    public ResponseEntity<Map<String, Object>> seedHistory(
            @RequestParam(defaultValue = "14") int days) {
        int written = historicalSeedService.seed(days);
        return ResponseEntity.ok(Map.of(
                "message", "backfilled synthetic history",
                "events", written,
                "days", Math.max(1, Math.min(days, 30))));
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
