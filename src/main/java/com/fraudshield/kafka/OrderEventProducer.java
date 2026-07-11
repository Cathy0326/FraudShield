package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Component
public class OrderEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    @Value("${fraudshield.kafka.topic.order-events}")
    private String topic;

    private final KafkaTemplate<String, Order> kafkaTemplate;
    private final Random random = new Random();

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
     * 发送5条测试订单，覆盖所有规则场景
     * Sends 5 test orders that exercise every rule in the engine.
     *
     * <p>IP和金额都做了随机化：早期版本用5个固定IP和固定金额，连点几次按钮后
     * 每个IP在5分钟窗口内都超过FrequentIpRule的限额，于是**所有**测试订单
     * （包括故意设计成NORMAL的那单）全部变成HIGH —— demo完全失真。
     * 随机IP让重复点击不会在滑动窗口里堆积；黑名单IP 10.0.0.1除外，
     * 它必须固定才能命中BlacklistRule。金额在各场景的规则阈值内随机抖动。
     * IPs and amounts are randomized. Earlier versions reused 5 fixed IPs and fixed
     * amounts, so a few button clicks pushed every IP over FrequentIpRule's 5-minute
     * limit and ALL test orders — including the deliberately NORMAL one — came out
     * HIGH, ruining the demo. Random IPs keep repeated clicks from piling up in the
     * sliding window; the blacklisted IP 10.0.0.1 stays fixed because BlacklistRule
     * must keep hitting it. Amounts jitter within each scenario's rule thresholds.
     */
    public void sendTestOrders() {
        List<Order> testOrders = List.of(
            // 1. HighAmountNewUserRule → MEDIUM (USER-TEST-001 created 2h ago, amount > 100)
            new Order(uid(), "USER-TEST-001", 120.0 + random.nextInt(180), randomIp(),
                    "DEV-1", LocalDateTime.now()),
            // 2. BlacklistRule (userId) → HIGH
            new Order(uid(), "USER-BAD-001",  30.0 + random.nextInt(60), randomIp(),
                    "DEV-2", LocalDateTime.now()),
            // 3. AbnormalAmountRule → MEDIUM (USER-TEST-002 avg=50, amount > 50*3)
            new Order(uid(), "USER-TEST-002", 160.0 + random.nextInt(160), randomIp(),
                    "DEV-3", LocalDateTime.now()),
            // 4. BlacklistRule (ip) → HIGH (ip 10.0.0.1 is blacklisted — must stay fixed)
            new Order(uid(), "USER-NORMAL",   20.0 + random.nextInt(70), "10.0.0.1",
                    "DEV-4", LocalDateTime.now()),
            // 5. NORMAL — no rule triggered (amount above card-testing probe range,
            //    below every amount threshold; fresh IP avoids the velocity window)
            new Order(uid(), "USER-NORMAL-2", 15.0 + random.nextInt(75), randomIp(),
                    "DEV-5", LocalDateTime.now())
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

    /**
     * TEST-NET-1网段（192.0.2.0/24，RFC 5737）内的随机IP —— 专用于文档/测试，
     * 不会与模拟器的固定IP池或任何真实地址冲突。254个地址对5分钟窗口足够稀疏：
     * 同一IP要被随机命中6次才会触发FrequentIpRule，重度连点也到不了。
     * Random IP from TEST-NET-1 (192.0.2.0/24, RFC 5737) — reserved for docs/tests,
     * never colliding with the simulator's fixed pools or real addresses. 254
     * addresses keep the 5-minute window sparse: one IP would need 6 random hits
     * to trip FrequentIpRule, which even heavy button-mashing won't produce.
     */
    private String randomIp() {
        return "192.0.2." + (1 + random.nextInt(254));
    }
}
