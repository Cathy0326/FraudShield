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
    void sendTestOrders_sendsExactlyFiveMessages() {
        producer.sendTestOrders();

        verify(kafkaTemplate, times(5)).send(anyString(), anyString(), any(Order.class));
    }
}
