package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OrderSimulatorTest {

    @Mock OrderEventProducer producer;

    private OrderSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new OrderSimulator(producer);
    }

    private List<Order> sample(int minOrders) {
        List<Order> orders = new ArrayList<>();
        while (orders.size() < minOrders) {
            orders.addAll(simulator.nextOrders());
        }
        return orders;
    }

    @Test
    void everyOrder_hasAllRequiredFields() {
        for (Order o : sample(200)) {
            assertThat(o.getOrderId()).startsWith("ORD-");
            assertThat(o.getUserId()).isNotBlank();
            assertThat(o.getAmount()).isPositive();
            assertThat(o.getIpAddress()).isNotBlank();
            assertThat(o.getDeviceId()).isNotBlank();
            assertThat(o.getTimestamp()).isNotNull();
        }
    }

    @Test
    void orderIds_areUnique() {
        List<Order> orders = sample(500);

        assertThat(orders.stream().map(Order::getOrderId).distinct().count())
                .isEqualTo(orders.size());
    }

    @Test
    void trafficMix_isMostlyNormalWithSomeRuleTrippers() {
        // 场景占比：正常77%，其余按各规则的触发阈值校准（见OrderSimulator注释）
        // ~77% normal; scenario shares are calibrated to each rule's threshold
        List<Order> orders = sample(600);

        long blacklistIp = orders.stream().filter(o -> "10.0.0.1".equals(o.getIpAddress())).count();
        long burst       = orders.stream().filter(o -> "77.77.77.77".equals(o.getIpAddress())).count();
        long youngAcct   = orders.stream().filter(o -> "USER-TEST-001".equals(o.getUserId())).count();
        long spike       = orders.stream().filter(o -> "USER-TEST-002".equals(o.getUserId())).count();
        long cardTest    = orders.stream().filter(o -> "66.66.66.66".equals(o.getIpAddress())).count();
        long suspicious  = blacklistIp + burst + youngAcct + spike + cardTest;

        // 可疑订单约27%（突发场景一次3单推高了订单占比）；宽容差防偶发失败
        // ~27% of ORDERS are suspicious (the 3-at-once burst inflates its share);
        // generous tolerance keeps the test stable
        assertThat(suspicious).isBetween((long) (600 * 0.15), (long) (600 * 0.42));
        // 每种场景都必须出现 —— 否则某条规则在demo里永远不亮
        // Every scenario must occur, or some rule never lights up in the demo
        assertThat(blacklistIp).isPositive();
        assertThat(burst).isPositive();
        assertThat(youngAcct).isPositive();
        assertThat(spike).isPositive();
        assertThat(cardTest).isPositive();
    }

    @Test
    void velocityBurst_emitsMultipleOrdersAtOnce() {
        // 突发场景必须一次多单 —— 单发时它从未真正超过频率阈值
        // The burst must be multi-order; emitted singly it never crossed the limit
        for (int i = 0; i < 2000; i++) {
            List<Order> batch = simulator.nextOrders();
            if (!batch.isEmpty() && "77.77.77.77".equals(batch.get(0).getIpAddress())) {
                assertThat(batch).hasSize(3);
                assertThat(batch).allMatch(o -> "77.77.77.77".equals(o.getIpAddress()));
                return;
            }
        }
        throw new AssertionError("No velocity burst produced in 2000 draws");
    }

    @Test
    void normalTraffic_spreadsAcrossManyStableIdentities() {
        List<Order> normal = sample(2000).stream()
                .filter(o -> o.getUserId().startsWith("USER-SIM-"))
                .collect(Collectors.toList());

        // Zipf长尾：正常流量必须摊开在大量IP上 —— 5个IP扛全部流量正是之前
        // FrequentIpRule被正常流量刷屏的根因
        // The Zipf tail must spread traffic over many IPs; 5 IPs carrying everything
        // was exactly why FrequentIpRule fired on normal traffic nonstop
        Map<String, Long> perIp = normal.stream()
                .collect(Collectors.groupingBy(Order::getIpAddress, Collectors.counting()));
        assertThat(perIp.size()).isGreaterThan(50);

        // 最重度用户约占正常流量3.7% —— 上限放宽到10%仍足以证明没有单IP热点
        // Heaviest user carries ~3.7% of normal traffic; a 10% ceiling still proves
        // no single-IP hotspot exists
        long maxPerIp = perIp.values().stream().mapToLong(Long::longValue).max().orElse(0);
        assertThat((double) maxPerIp / normal.size()).isLessThan(0.10);

        // 身份稳定：同一用户的所有订单来自同一IP和设备
        // Identity stability: every order from a user shares that user's IP and device
        Map<String, List<Order>> perUser = normal.stream()
                .collect(Collectors.groupingBy(Order::getUserId));
        perUser.forEach((user, orders) -> {
            assertThat(orders.stream().map(Order::getIpAddress).distinct()).hasSize(1);
            assertThat(orders.stream().map(Order::getDeviceId).distinct()).hasSize(1);
        });
    }

    @Test
    void amounts_areLogNormalWithinClipBounds() {
        List<Order> normal = sample(1000).stream()
                .filter(o -> o.getUserId().startsWith("USER-SIM-"))
                .collect(Collectors.toList());

        assertThat(normal).allMatch(o -> o.getAmount() >= 8.0 && o.getAmount() <= 400.0);
        // 右偏分布：均值应高于中位数 / right-skewed: mean above median
        List<Double> sorted = normal.stream().map(Order::getAmount).sorted().collect(Collectors.toList());
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.get(sorted.size() / 2);
        assertThat(mean).isGreaterThan(median);
    }

    @Test
    void nhppRate_staysWithinConfiguredBand() {
        // λ(t) ∈ [0.4, 1.4]：泊松事件数非负，均值约等于λ
        // Rate stays in band; Poisson draws are non-negative with mean ≈ λ
        double rate = simulator.currentRate();
        assertThat(rate).isBetween(0.4, 1.4);

        int total = 0;
        for (int i = 0; i < 2000; i++) {
            int k = simulator.poisson(0.9);
            assertThat(k).isGreaterThanOrEqualTo(0);
            total += k;
        }
        assertThat(total / 2000.0).isBetween(0.7, 1.1);
    }
}
