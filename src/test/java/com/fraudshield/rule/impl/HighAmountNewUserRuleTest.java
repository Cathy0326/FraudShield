package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HighAmountNewUserRuleTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    private HighAmountNewUserRule rule;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rule = new HighAmountNewUserRule(redisTemplate);
    }

    private Order order(String userId, double amount) {
        return new Order("ORD-1", userId, amount, "1.2.3.4", "DEV-1", LocalDateTime.now());
    }

    @Test
    void newUser_highAmount_triggersMedium() {
        // 账号2小时前创建，在24小时窗口内
        long createdAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;
        when(valueOps.get(anyString())).thenReturn(String.valueOf(createdAt));

        RiskResult result = rule.evaluate(order("USER-1", 150.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getRiskScore()).isEqualTo(0.6);
        assertThat(result.getTriggeredRules()).containsExactly("HighAmountNewUserRule");
    }

    @Test
    void oldUser_highAmount_returnsNormal() {
        // 账号48小时前创建，超出24小时窗口
        long createdAt = System.currentTimeMillis() - 48 * 60 * 60 * 1000L;
        when(valueOps.get(anyString())).thenReturn(String.valueOf(createdAt));

        RiskResult result = rule.evaluate(order("USER-2", 150.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void newUser_lowAmount_returnsNormal() {
        // 新用户但金额低（≤100），不触发
        long createdAt = System.currentTimeMillis() - 2 * 60 * 60 * 1000L;
        when(valueOps.get(anyString())).thenReturn(String.valueOf(createdAt));

        RiskResult result = rule.evaluate(order("USER-3", 50.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void missingCreationKey_highAmount_triggersMedium() {
        // 保守策略：Redis中无创建时间 → 视为新用户
        // Conservative: missing key → treated as new user
        when(valueOps.get(anyString())).thenReturn(null);

        RiskResult result = rule.evaluate(order("USER-UNKNOWN", 150.0));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }
}
