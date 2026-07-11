package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderEventProducerTest {

    @Mock KafkaTemplate<String, Order> kafkaTemplate;

    private OrderEventProducer producer;

    @BeforeEach
    void setUp() {
        producer = new OrderEventProducer(kafkaTemplate);
        // Inject @Value field without a Spring context
        ReflectionTestUtils.setField(producer, "topic", "order-events");
    }

    @Test
    void sendOrder_callsKafkaTemplateWithCorrectTopicAndKey() {
        Order order = new Order("ORD-001", "USER-1", 50.0, "1.2.3.4", "DEV-1", LocalDateTime.now());

        producer.sendOrder(order);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Order>  valueCaptor = ArgumentCaptor.forClass(Order.class);

        verify(kafkaTemplate).send(topicCaptor.capture(), keyCaptor.capture(), valueCaptor.capture());

        assertThat(topicCaptor.getValue()).isEqualTo("order-events");
        assertThat(keyCaptor.getValue()).isEqualTo("ORD-001");
        assertThat(valueCaptor.getValue()).isEqualTo(order);
    }

    @Test
    void sendTestOrders_sendsExactlyEightMessages() {
        producer.sendTestOrders();

        verify(kafkaTemplate, times(8)).send(anyString(), anyString(), any(Order.class));
    }

    @Test
    void sendTestOrders_randomizedOrdersStillHitTheirIntendedRules() {
        // IP/金额随机化不能破坏各场景的规则语义 —— 这里锁死每个场景的阈值不变式
        // Randomization must not break scenario semantics; pin each rule threshold invariant
        producer.sendTestOrders();

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(kafkaTemplate, times(8)).send(anyString(), anyString(), captor.capture());
        var orders = captor.getAllValues();

        // 1. HighAmountNewUserRule needs amount > 100
        assertThat(orders.get(0).getAmount()).isGreaterThan(100.0);
        // 3. AbnormalAmountRule needs amount > 3x the seeded avg of 50
        assertThat(orders.get(2).getAmount()).isGreaterThan(150.0);
        // 4. The blacklisted IP must stay fixed or BlacklistRule stops firing
        assertThat(orders.get(3).getIpAddress()).isEqualTo("10.0.0.1");
        // 5. NORMAL scenario: above the card-testing probe range, below every amount threshold
        assertThat(orders.get(4).getAmount()).isGreaterThan(10.0).isLessThan(100.0);

        // 随机IP来自TEST-NET-1，重复点击不会在FrequentIpRule窗口里堆积
        // Random IPs come from TEST-NET-1 so repeated clicks don't pile up per IP
        for (int i : new int[] {0, 1, 2, 4}) {
            assertThat(orders.get(i).getIpAddress()).startsWith("192.0.2.");
        }

        // 6-8. Drop-address cluster: 3 distinct identities → ONE shipping address,
        // each with a different billing address (AVS mismatch) — trips AddressPatternRule
        var mule = orders.subList(5, 8).stream()
                .filter(o -> o.getUserId().startsWith("USER-MULE-")).toList();
        assertThat(mule).hasSize(3);
        assertThat(mule.stream().map(Order::getUserId).distinct().count()).isEqualTo(3);
        assertThat(mule.stream().map(Order::getShippingAddress).distinct()).hasSize(1);
        assertThat(mule).allMatch(o -> !o.getShippingAddress().equals(o.getBillingAddress()));
    }
}
