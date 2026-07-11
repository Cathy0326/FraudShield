package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardTestingRuleTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private CardTestingRule newRule() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        return new CardTestingRule(redisTemplate);
    }

    private Order smallOrder(String userId, String ip, String deviceId) {
        return new Order("ORD-001", userId, 2.50, ip, deviceId, LocalDateTime.now());
    }

    private Set<String> members(String... userOrderPairs) {
        return new LinkedHashSet<>(Set.of(userOrderPairs));
    }

    @Test
    void whenAmountAboveProbeRange_thenNormalAndNotTracked() {
        // $500 is not a card-testing probe — the order must not even enter the window
        CardTestingRule rule = new CardTestingRule(redisTemplate);
        Order order = new Order("ORD-001", "USER-1", 500.0, "10.0.0.1", "DEV-1", LocalDateTime.now());

        RiskResult result = rule.evaluate(order);

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getTriggeredRules()).isEmpty();
        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void whenFewMicroCharges_thenNormal() {
        // Only 2 micro-charges in the window — below the 4-order threshold
        CardTestingRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-B|ORD-2"));

        RiskResult result = rule.evaluate(smallOrder("USER-B", "10.0.0.1", "DEV-1"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getRiskScore()).isEqualTo(0.0);
    }

    @Test
    void whenManyMicroChargesButSingleIdentity_thenNormal() {
        // 5 micro-charges but all from ONE user — an impatient shopper, not card testing
        CardTestingRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-A|ORD-2", "USER-A|ORD-3",
                        "USER-A|ORD-4", "USER-A|ORD-5"));

        RiskResult result = rule.evaluate(smallOrder("USER-A", "10.0.0.1", "DEV-1"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getTriggeredRules()).isEmpty();
    }

    @Test
    void whenManyMicroChargesFromManyIdentities_thenHigh() {
        // The John Doe pattern: 5 micro-charges, 4 distinct identities, one IP
        CardTestingRule rule = newRule();
        when(zSetOperations.range(startsWith("cardtest:ip:"), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-B|ORD-2", "USER-C|ORD-3",
                        "USER-D|ORD-4", "USER-A|ORD-5"));
        when(zSetOperations.range(startsWith("cardtest:device:"), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-5"));

        RiskResult result = rule.evaluate(smallOrder("USER-A", "66.66.66.66", "DEV-1"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(0.95);
        assertThat(result.getTriggeredRules()).containsExactly("CardTestingRule");
        assertThat(result.getExplanation())
                .contains("66.66.66.66").contains("5 micro-charges").contains("4");
    }

    @Test
    void whenPatternOnDeviceOnly_thenHighWithDeviceExplanation() {
        // Attacker rotates IPs (proxy list) but keeps one device fingerprint —
        // the device dimension must catch what the IP dimension misses
        CardTestingRule rule = newRule();
        when(zSetOperations.range(startsWith("cardtest:ip:"), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-5"));
        when(zSetOperations.range(startsWith("cardtest:device:"), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-B|ORD-2", "USER-C|ORD-3",
                        "USER-D|ORD-4"));

        RiskResult result = rule.evaluate(smallOrder("USER-A", "10.9.9.9", "DEV-EMU-77"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getExplanation()).contains("device DEV-EMU-77");
    }
}
