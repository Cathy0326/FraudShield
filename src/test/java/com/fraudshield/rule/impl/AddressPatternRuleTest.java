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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressPatternRuleTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private AddressPatternRule newRule() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        return new AddressPatternRule(redisTemplate);
    }

    private Order order(String userId, String shipping, String billing) {
        return new Order("ORD-001", userId, 250.0, "1.2.3.4", "DEV-1",
                LocalDateTime.now(), shipping, billing);
    }

    private Set<String> members(String... userOrderPairs) {
        return new LinkedHashSet<>(Set.of(userOrderPairs));
    }

    @Test
    void whenNoShippingAddress_thenNormalAndNotTracked() {
        AddressPatternRule rule = new AddressPatternRule(redisTemplate);

        RiskResult result = rule.evaluate(order("USER-A", null, "1 Main St"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getTriggeredRules()).isEmpty();
        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void whenSingleIdentityToAddress_thenNormal() {
        // 一个用户反复发往自己家 —— 正常，不该命中 / one user shipping to their own home
        AddressPatternRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-A|ORD-2", "USER-A|ORD-3"));

        RiskResult result = rule.evaluate(order("USER-A", "1 Main St", "1 Main St"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getTriggeredRules()).isEmpty();
    }

    @Test
    void whenBillingDiffersButSingleIdentity_thenStillNormal() {
        // 账单≠收货单独出现不触发（礼物/代购常见）—— 保持精度
        // Billing≠shipping alone must not fire (gifts are common) - precision guard
        AddressPatternRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1"));

        RiskResult result = rule.evaluate(order("USER-A", "1 Main St", "99 Other Ave"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void whenManyIdentitiesToOneAddress_thenHigh() {
        // 代收点：3个不同身份发往同一地址 / drop point: 3 identities, one address
        AddressPatternRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-B|ORD-2", "USER-C|ORD-3"));

        RiskResult result = rule.evaluate(order("USER-C", "1 Reship Way", "1 Reship Way"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(0.9);
        assertThat(result.getTriggeredRules()).containsExactly("AddressPatternRule");
        assertThat(result.getExplanation()).contains("3 different user").contains("reshipping-mule");
    }

    @Test
    void whenDropPatternWithBillingMismatch_thenHighWithAvsNote() {
        // 代收点 + 账单不符：说明里应点出AVS不符 / drop pattern + AVS mismatch note
        AddressPatternRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-B|ORD-2", "USER-C|ORD-3", "USER-D|ORD-4"));

        RiskResult result = rule.evaluate(order("USER-D", "1 Reship Way", "500 Cardholder Rd"));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getExplanation()).contains("AVS mismatch");
    }

    @Test
    void addressesAreNormalized_whitespaceAndCaseIgnored() {
        // 归一化后 " 1  RESHIP way " 与 "1 reship way" 视为同一key
        AddressPatternRule rule = newRule();
        when(zSetOperations.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(members("USER-A|ORD-1", "USER-B|ORD-2", "USER-C|ORD-3"));

        RiskResult result = rule.evaluate(order("USER-C", "  1  RESHIP   Way ", null));

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        // key应为归一化后的地址 / the ZSET key must be the normalized address
        verify(zSetOperations).removeRangeByScore(eq("addr:ship:1 reship way"), eq(0.0), anyDouble());
    }
}
