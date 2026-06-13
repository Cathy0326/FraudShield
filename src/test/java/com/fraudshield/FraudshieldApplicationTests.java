package com.fraudshield;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Uses the default application.yml; H2 starts in-memory so no external DB needed.
// Kafka/Redis connections are attempted but failures won't abort the context load
// because we haven't set spring.kafka.bootstrap-servers to a required-or-die value.
@SpringBootTest
@ActiveProfiles("test")
class FraudshieldApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the Spring context assembles without errors.
    }
}
