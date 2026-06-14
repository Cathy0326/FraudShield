package com.fraudshield.rule;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.rule.impl.FrequentIpRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FrequentIpRuleTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private FrequentIpRule rule;

    @BeforeEach
    void setUp() {
        // Wire the ZSet mock into the template mock before each test.
        // This is the most important setup step — every call to opsForZSet()
        // must return our mock, otherwise NPEs fire inside FrequentIpRule.
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        rule = new FrequentIpRule(redisTemplate);
    }

    private Order buildOrder(String ip) {
        return new Order("ORD-001", "USER-1", 99.0, ip, "DEVICE-1", LocalDateTime.now());
    }

    @Test
    void whenIpCountBelowThreshold_thenNormal() {
        // 3 orders in the window → below the limit of 5 → NORMAL
        when(zSetOperations.zCard(anyString())).thenReturn(3L);

        RiskResult result = rule.evaluate(buildOrder("10.0.0.1"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getRiskScore()).isEqualTo(0.0);
        assertThat(result.getTriggeredRules()).isEmpty();
    }

    @Test
    void whenIpCountExceedsThreshold_thenHigh() {
        // 6 orders in the window → exceeds limit of 5 → HIGH
        when(zSetOperations.zCard(anyString())).thenReturn(6L);

        RiskResult result = rule.evaluate(buildOrder("10.0.0.2"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(0.9);
        assertThat(result.getTriggeredRules()).containsExactly("FrequentIpRule");
        assertThat(result.getExplanation()).contains("10.0.0.2").contains("6");
    }
}
