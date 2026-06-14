package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AbnormalAmountRuleTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private AbnormalAmountRule rule;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rule = new AbnormalAmountRule(redisTemplate);
    }

    private Order order(String userId, double amount) {
        return new Order("ORD-1", userId, amount, "1.2.3.4", "DEV-1", LocalDateTime.now());
    }

    @Test
    void amountFourTimesAverage_triggersMedium() {
        // 200 > 50 * 3 → MEDIUM
        when(valueOps.get(anyString())).thenReturn("50.0");

        RiskResult result = rule.evaluate(order("USER-1", 200.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getRiskScore()).isEqualTo(0.65);
        assertThat(result.getTriggeredRules()).containsExactly("AbnormalAmountRule");
    }

    @Test
    void amountTwoTimesAverage_returnsNormal() {
        // 100 < 50 * 3 → NORMAL
        when(valueOps.get(anyString())).thenReturn("50.0");

        RiskResult result = rule.evaluate(order("USER-2", 100.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void noAverageKey_returnsNormal() {
        // 没有历史数据 → 跳过规则 / No history → skip rule
        when(valueOps.get(anyString())).thenReturn(null);

        RiskResult result = rule.evaluate(order("USER-NEW", 999.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void averageIsUpdatedAfterEvaluation() {
        // EMA更新验证：newAvg = 50 * 0.9 + 100 * 0.1 = 55.0
        // Verify EMA update: newAvg = 50 * 0.9 + 100 * 0.1 = 55.0
        when(valueOps.get(anyString())).thenReturn("50.0");

        rule.evaluate(order("USER-3", 100.0));

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), valueCaptor.capture(), eq(30L), eq(TimeUnit.DAYS));

        double updatedAvg = Double.parseDouble(valueCaptor.getValue());
        assertThat(updatedAvg).isEqualTo(55.0);
    }
}
