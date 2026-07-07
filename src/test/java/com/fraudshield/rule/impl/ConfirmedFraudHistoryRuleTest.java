package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.repository.RiskEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@ExtendWith(MockitoExtension.class)
class ConfirmedFraudHistoryRuleTest {

    @Mock RiskEventRepository repository;

    private ConfirmedFraudHistoryRule rule;

    @BeforeEach
    void setUp() {
        rule = new ConfirmedFraudHistoryRule(repository);
    }

    private Order order() {
        return new Order("ORD-1", "USER-1", 50.0, "9.9.9.9", "DEV-1", LocalDateTime.now());
    }

    @Test
    void userWithConfirmedFraudHistory_isHigh() {
        // 惯犯：不管这一单金额多小 / repeat offender — regardless of this order's size
        when(repository.countByUserIdAndReviewStatus("USER-1", "CONFIRMED_FRAUD")).thenReturn(2L);

        RiskResult result = rule.evaluate(order());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getRiskScore()).isEqualTo(0.95);
        assertThat(result.getTriggeredRules()).containsExactly("ConfirmedFraudHistoryRule");
        assertThat(result.getExplanation()).contains("2 confirmed fraud order(s)");
        // 用户自身命中时不再查IP / IP check skipped when the user themselves matched
        verify(repository, never()).existsByIpAddressAndReviewStatusAndUserIdNot(any(), any(), any());
    }

    @Test
    void cleanUserOnFraudsterIp_isMedium() {
        // 团伙换账号不换基础设施 / rings rotate accounts, reuse infrastructure
        when(repository.countByUserIdAndReviewStatus("USER-1", "CONFIRMED_FRAUD")).thenReturn(0L);
        when(repository.existsByIpAddressAndReviewStatusAndUserIdNot("9.9.9.9", "CONFIRMED_FRAUD", "USER-1"))
                .thenReturn(true);

        RiskResult result = rule.evaluate(order());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getRiskScore()).isEqualTo(0.7);
        assertThat(result.getExplanation()).contains("another user's confirmed fraud history");
    }

    @Test
    void cleanUserAndCleanIp_isNormal() {
        when(repository.countByUserIdAndReviewStatus("USER-1", "CONFIRMED_FRAUD")).thenReturn(0L);
        when(repository.existsByIpAddressAndReviewStatusAndUserIdNot(anyString(), anyString(), anyString()))
                .thenReturn(false);

        RiskResult result = rule.evaluate(order());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getRiskScore()).isEqualTo(0.0);
        assertThat(result.getTriggeredRules()).isEmpty();
    }

    @Test
    void nullCountFromRepository_isTreatedAsZero() {
        when(repository.countByUserIdAndReviewStatus("USER-1", "CONFIRMED_FRAUD")).thenReturn(null);
        when(repository.existsByIpAddressAndReviewStatusAndUserIdNot(anyString(), anyString(), anyString()))
                .thenReturn(false);

        RiskResult result = rule.evaluate(order());

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void nullIpAddress_skipsIpCheckAndIsNormal() {
        when(repository.countByUserIdAndReviewStatus("USER-1", "CONFIRMED_FRAUD")).thenReturn(0L);
        Order noIp = new Order("ORD-1", "USER-1", 50.0, null, "DEV-1", LocalDateTime.now());

        RiskResult result = rule.evaluate(noIp);

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        verify(repository, never()).existsByIpAddressAndReviewStatusAndUserIdNot(any(), any(), any());
    }
}
