package com.fraudshield.rule;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.service.LogisticModelService;
import com.fraudshield.service.RuleConfigService;
import com.fraudshield.service.RuleWeightService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RiskDetectionEngineTest {

    @Mock RuleWeightService weights;
    @Mock LogisticModelService logisticModel;
    @Mock RuleConfigService ruleConfig;

    private final Order order = new Order("ORD-1", "USER-1", 100.0, "1.2.3.4", "DEV-1",
            LocalDateTime.now());

    @BeforeEach
    void defaultWeights() {
        lenient().when(weights.weightFor(anyString())).thenReturn(1.0);
        // 默认所有规则启用、无权重覆盖 / all rules enabled, no override by default.
        // weightOverride 必须显式返回null —— Mockito对Double返回类型默认给0.0，会把权重压成0
        // weightOverride must be stubbed to null explicitly: Mockito defaults a Double-returning
        // method to 0.0, which would force the combination weight to zero
        lenient().when(ruleConfig.isEnabled(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        lenient().when(ruleConfig.weightOverride(org.mockito.ArgumentMatchers.any())).thenReturn(null);
        // 默认无训练模型 → 纯noisy-OR / untrained by default - pure noisy-OR
        lenient().when(logisticModel.predict(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.OptionalDouble.empty());
    }

    private RiskRule rule(String name, RiskLevel level, double score) {
        // 具名匿名类而非lambda —— lambda的getRuleName()是合成类名，会干扰引擎的启用判断
        // Named anonymous class, not a lambda: a lambda's getRuleName() is a synthetic
        // class name that muddies the engine's enabled check
        return new RiskRule() {
            @Override public String getRuleName() { return name; }
            @Override public RiskResult evaluate(Order o) {
                return RiskResult.builder()
                        .orderId(o.getOrderId())
                        .riskLevel(level)
                        .riskScore(score)
                        .triggeredRules(score > 0 ? List.of(name) : List.of())
                        .explanation(name + " fired")
                        .build();
            }
        };
    }

    private RiskDetectionEngine engine(RiskRule... rules) {
        return new RiskDetectionEngine(List.of(rules), new SimpleMeterRegistry(), weights, logisticModel, ruleConfig);
    }

    @Test
    void singleHighRule_staysHigh_sameAsOldMaxBehavior() {
        RiskResult result = engine(rule("Blacklist", RiskLevel.HIGH, 1.0)).evaluate(order);

        assertThat(result.getRiskScore()).isEqualTo(1.0);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getTriggeredRules()).containsExactly("Blacklist");
    }

    @Test
    void corroboratingMediumRules_escalateToHigh() {
        // 旧max行为下两条0.6只得0.6 —— 相互印证的证据现在会叠加
        // Under old max, two 0.6s scored 0.6; corroborating evidence now compounds:
        // 1 - (1-0.6)(1-0.6) = 0.84 -> HIGH
        RiskResult result = engine(
                rule("RuleA", RiskLevel.MEDIUM, 0.6),
                rule("RuleB", RiskLevel.MEDIUM, 0.6)).evaluate(order);

        assertThat(result.getRiskScore()).isEqualTo(0.84);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getTriggeredRules()).containsExactlyInAnyOrder("RuleA", "RuleB");
    }

    @Test
    void lowPrecisionRule_speaksMoreQuietly() {
        // 该规则的历史精度只有30% → 权重0.3 → 单独命中0.6只得0.18 → NORMAL
        // A 30%-precision rule at weight 0.3: 0.3*0.6 = 0.18 -> below LOW threshold
        lenient().when(weights.weightFor("NoisyRule")).thenReturn(0.3);

        RiskResult result = engine(rule("NoisyRule", RiskLevel.MEDIUM, 0.6)).evaluate(order);

        assertThat(result.getRiskScore()).isEqualTo(0.18);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
    }

    @Test
    void noRulesTriggered_isNormal() {
        RiskResult result = engine(rule("Quiet", RiskLevel.NORMAL, 0.0)).evaluate(order);

        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.NORMAL);
        assertThat(result.getRiskScore()).isEqualTo(0.0);
        assertThat(result.getTriggeredRules()).isEmpty();
        assertThat(result.getExplanation()).isEqualTo("No rules triggered");
    }

    @Test
    void combinedScore_neverExceedsOne() {
        RiskResult result = engine(
                rule("A", RiskLevel.HIGH, 1.0),
                rule("B", RiskLevel.HIGH, 1.0),
                rule("C", RiskLevel.HIGH, 0.9)).evaluate(order);

        assertThat(result.getRiskScore()).isLessThanOrEqualTo(1.0);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void trainedModel_blendsFiftyFiftyWithNoisyOr() {
        // noisy-OR 0.6 + 模型 1.0 → (0.6+1.0)/2 = 0.8 → HIGH
        // A confident model escalates what the heuristic alone would call MEDIUM
        lenient().when(logisticModel.predict(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.OptionalDouble.of(1.0));
        lenient().when(logisticModel.trainedOnSamples()).thenReturn(42);

        RiskResult result = engine(rule("RuleA", RiskLevel.MEDIUM, 0.6)).evaluate(order);

        assertThat(result.getRiskScore()).isEqualTo(0.8);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getExplanation()).contains("ML fraud probability 1.00")
                .contains("42 labeled orders");
    }

    @Test
    void skepticalModel_dampensTheHeuristic() {
        // 模型学到这个组合多为误报 → 分数被压低 / model learned this combo is noise
        lenient().when(logisticModel.predict(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.OptionalDouble.of(0.1));

        RiskResult result = engine(rule("NoisyCombo", RiskLevel.MEDIUM, 0.7)).evaluate(order);

        assertThat(result.getRiskScore()).isEqualTo(0.4);
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.LOW); // 0.7 alone would be MEDIUM

    }

    @Test
    void explanations_areJoinedFromAllTriggeredRules() {
        RiskResult result = engine(
                rule("A", RiskLevel.MEDIUM, 0.6),
                rule("B", RiskLevel.LOW, 0.3)).evaluate(order);

        assertThat(result.getExplanation()).contains("A fired").contains("B fired");
    }
}
