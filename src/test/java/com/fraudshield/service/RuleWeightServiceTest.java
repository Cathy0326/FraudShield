package com.fraudshield.service;

import com.fraudshield.dto.RulePrecisionDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuleWeightServiceTest {

    @Mock RiskEventService riskEventService;

    private RuleWeightService service;

    @BeforeEach
    void setUp() {
        service = new RuleWeightService(riskEventService);
    }

    private RulePrecisionDTO stat(String rule, long reviewed, Double precision) {
        return RulePrecisionDTO.builder()
                .rule(rule).totalHits(reviewed).reviewedHits(reviewed)
                .precision(precision).build();
    }

    @Test
    void unmeasuredRule_defaultsToFullWeight() {
        assertThat(service.weightFor("AnythingRule")).isEqualTo(1.0);
    }

    @Test
    void measuredRule_getsPrecisionDerivedWeight() {
        when(riskEventService.getRulePrecision()).thenReturn(List.of(
                stat("GoodRule", 10, 90.0)));

        service.refresh();

        assertThat(service.weightFor("GoodRule")).isEqualTo(0.9);
    }

    @Test
    void tooFewLabels_keepsFullWeight() {
        // 2条标注不足以下结论 —— 未测量≠不可信 / 2 labels prove nothing yet
        when(riskEventService.getRulePrecision()).thenReturn(List.of(
                stat("YoungRule", 2, 0.0)));

        service.refresh();

        assertThat(service.weightFor("YoungRule")).isEqualTo(1.0);
    }

    @Test
    void terribleRule_isFlooredNotSilenced() {
        // 0%精度也保留0.2下限 —— 命中本身仍是弱信号 / floor at 0.2, never zero
        when(riskEventService.getRulePrecision()).thenReturn(List.of(
                stat("NoisyRule", 20, 0.0)));

        service.refresh();

        assertThat(service.weightFor("NoisyRule")).isEqualTo(0.2);
    }

    @Test
    void refreshFailure_keepsPreviousWeights() {
        when(riskEventService.getRulePrecision()).thenReturn(List.of(
                stat("GoodRule", 10, 80.0)));
        service.refresh();

        when(riskEventService.getRulePrecision()).thenThrow(new RuntimeException("db down"));
        service.refresh(); // must not throw

        assertThat(service.weightFor("GoodRule")).isEqualTo(0.8);
    }
}
