package com.fraudshield.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${fraudshield.kafka.topic.order-events}")
    private String orderEventsTopic;

    // Declare the topic so Kafka auto-creates it with the right settings on startup.
    // KafkaAdmin (auto-configured by Spring) will handle creation if the broker is up.
    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(orderEventsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
