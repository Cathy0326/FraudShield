package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OrderSimulatorTest {

    @Mock OrderEventProducer producer;

    private OrderSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new OrderSimulator(producer);
    }

    private List<Order> sample(int n) {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orders.add(simulator.nextOrder());
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
        // 500单足以让70/30比例稳定显现（二项分布，±5%内）
        // 500 samples make the 70/30 split statistically stable (within ~±5%)
        List<Order> orders = sample(500);

        long blacklistIp = orders.stream().filter(o -> "10.0.0.1".equals(o.getIpAddress())).count();
        long burst       = orders.stream().filter(o -> "77.77.77.77".equals(o.getIpAddress())).count();
        long youngAcct   = orders.stream().filter(o -> "USER-TEST-001".equals(o.getUserId())).count();
        long spike       = orders.stream().filter(o -> "USER-TEST-002".equals(o.getUserId())).count();
        long cardTest    = orders.stream().filter(o -> "66.66.66.66".equals(o.getIpAddress())).count();
        long suspicious  = blacklistIp + burst + youngAcct + spike + cardTest;

        // 可疑流量约30%：给宽容差防止偶发失败 / ~30% suspicious, generous tolerance
        assertThat(suspicious).isBetween(90L, 210L);
        // 每种场景都必须出现 —— 否则某条规则在demo里永远不亮
        // Every scenario must occur, or some rule never lights up in the demo
        assertThat(blacklistIp).isPositive();
        assertThat(burst).isPositive();
        assertThat(youngAcct).isPositive();
        assertThat(spike).isPositive();
        assertThat(cardTest).isPositive();
    }
}
