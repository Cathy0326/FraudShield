package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 持续订单模拟器 — 让系统"活"起来的后台流量源
 * Continuous order simulator: a background traffic source that keeps the system alive.
 *
 * <p>Send Test Orders按钮只在点击时产生5单 —— dashboard曲线在两次点击之间是死的。
 * 模拟器每几秒发一单，按真实感的比例混合流量：大多数正常，少数触发各条规则。
 * 这让Grafana的每条曲线、React的每个KPI都持续有数据流过，demo不再需要人肉刷按钮。
 * The Send Test Orders button emits 5 orders per click — between clicks every curve
 * is flat. The simulator emits one order every few seconds with a realistic mix
 * (mostly normal, a minority tripping each rule), so every Grafana curve and React
 * KPI has continuous data without anyone mashing a button.
 *
 * <p>默认关闭：SIMULATOR_ENABLED=true开启（compose里默认开，方便本地demo；
 * 真实部署绝不该有合成流量，所以代码默认false）。
 * Off by default in code — enable with SIMULATOR_ENABLED=true. docker-compose
 * enables it for local demos; a real deployment must never emit synthetic traffic.
 */
@Component
@ConditionalOnProperty(name = "fraudshield.simulator.enabled", havingValue = "true")
public class OrderSimulator {

    private static final Logger log = LoggerFactory.getLogger(OrderSimulator.class);

    // 正常用户池 —— 大部分流量来自这里 / pool of ordinary users producing most traffic
    private static final List<String> NORMAL_USERS = List.of(
            "USER-ALICE", "USER-BOB", "USER-CAROL", "USER-DAVE", "USER-EVE");
    private static final List<String> NORMAL_IPS = List.of(
            "203.0.113.10", "203.0.113.11", "203.0.113.12", "198.51.100.7", "198.51.100.8");
    private static final List<String> DEVICES = List.of(
            "DEV-MAC-01", "DEV-WIN-02", "DEV-IOS-03", "DEV-AND-04", "DEV-WEB-05");

    private final OrderEventProducer producer;
    private final Random random = new Random();

    public OrderSimulator(OrderEventProducer producer) {
        this.producer = producer;
        log.info("OrderSimulator ENABLED - emitting one synthetic order every 4s "
                + "(~70% normal / ~30% rule-tripping)");
    }

    @Scheduled(fixedRate = 4_000, initialDelay = 15_000)
    public void emitOrder() {
        producer.sendOrder(nextOrder());
    }

    // 流量构成：70%正常，其余均分给各规则场景 —— 比例决定dashboard的"表情"
    // Traffic mix: 70% normal, the rest split across rule scenarios.
    Order nextOrder() {
        int roll = random.nextInt(100);
        if (roll < 70) {
            // 正常订单：常规用户、常规金额 / normal: ordinary user, ordinary amount
            return order(pick(NORMAL_USERS), 10.0 + random.nextInt(80), pick(NORMAL_IPS));
        }
        if (roll < 80) {
            // 黑名单IP命中（DataInitializer种子：10.0.0.1）/ blacklisted IP seed
            return order("USER-DRIFTER-" + random.nextInt(3), 20.0 + random.nextInt(60), "10.0.0.1");
        }
        if (roll < 88) {
            // 新账号大额（USER-TEST-001种子：账龄2小时）/ young account, high amount
            return order("USER-TEST-001", 120.0 + random.nextInt(200), pick(NORMAL_IPS));
        }
        if (roll < 95) {
            // 金额异常（USER-TEST-002种子：历史均值50）/ amount spike vs historical mean
            return order("USER-TEST-002", 180.0 + random.nextInt(150), pick(NORMAL_IPS));
        }
        // 高频攻击突发：同一IP连续命中FrequentIpRule / velocity burst from one IP
        return order("USER-BURST-" + random.nextInt(2), 15.0 + random.nextInt(30), "77.77.77.77");
    }

    private Order order(String userId, double amount, String ip) {
        return new Order(
                "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                userId,
                Math.round(amount * 100.0) / 100.0,
                ip,
                pick(DEVICES),
                LocalDateTime.now());
    }

    private String pick(List<String> pool) {
        return pool.get(random.nextInt(pool.size()));
    }
}
