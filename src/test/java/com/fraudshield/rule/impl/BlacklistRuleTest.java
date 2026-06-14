package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlacklistRuleTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock SetOperations<String, String> setOps;

    private BlacklistRule rule;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        rule = new BlacklistRule(redisTemplate);
    }

    private Order order(String userId, String ip) {
        return new Order("ORD-1", userId, 99.0, ip, "DEV-1", LocalDateTime.now());
    }

    @Test
    void userIdInBlacklist_returnsHigh() {
        when(setOps.isMember("blacklist:users", "BAD-USER")).thenReturn(true);
        when(setOps.isMember("blacklist:ips",   "1.2.3.4")).thenReturn(false);

        RiskResult result = rule.evaluate(order("BAD-USER", "1.2.3.4"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(1.0);
        assertThat(result.getTriggeredRules()).containsExactly("BlacklistRule");
        assertThat(result.getExplanation()).contains("blacklist:users");
    }

    @Test
    void ipInBlacklist_returnsHigh() {
        when(setOps.isMember("blacklist:users", "GOOD-USER")).thenReturn(false);
        when(setOps.isMember("blacklist:ips",   "10.0.0.1")).thenReturn(true);

        RiskResult result = rule.evaluate(order("GOOD-USER", "10.0.0.1"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(1.0);
        assertThat(result.getExplanation()).contains("blacklist:ips");
    }

    @Test
    void neitherInBlacklist_returnsNormal() {
        when(setOps.isMember("blacklist:users", "CLEAN-USER")).thenReturn(false);
        when(setOps.isMember("blacklist:ips",   "9.9.9.9")).thenReturn(false);

        RiskResult result = rule.evaluate(order("CLEAN-USER", "9.9.9.9"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getRiskScore()).isEqualTo(0.0);
    }

    @Test
    void bothInBlacklist_returnsHigh() {
        // userId检查优先返回，IP命中也一样触发HIGH
        // userId check fires first; either match is enough for HIGH
        when(setOps.isMember("blacklist:users", "BAD-USER")).thenReturn(true);
        when(setOps.isMember("blacklist:ips",   "10.0.0.1")).thenReturn(true);

        RiskResult result = rule.evaluate(order("BAD-USER", "10.0.0.1"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(1.0);
    }
}
