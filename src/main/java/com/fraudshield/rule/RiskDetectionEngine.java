package com.fraudshield.rule;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.service.LogisticModelService;
import com.fraudshield.service.RuleConfigService;
import com.fraudshield.service.RuleWeightService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 风险评分引擎 — 加权noisy-OR组合，替代原先的取最高分
 * Risk scoring engine: weighted noisy-OR combination, replacing pure max-score.
 *
 * <p>为什么弃用max：max忽略了相互印证的证据 —— 三条独立的中风险信号同时命中，
 * 显然比单条更可疑，但max下得分与单条完全相同。
 * Why max was retired: it ignores corroboration — three independent medium signals
 * firing together is clearly more suspicious than one, yet under max they score
 * identically.
 *
 * <p>组合公式 (the combination):
 * <pre>  combined = 1 − Π(1 − wᵢ·sᵢ)</pre>
 * noisy-OR：把每条规则视为独立的欺诈证据，任一证据为真则订单可疑。性质：
 * 单条规则时退化为 w·s（与旧行为一致）；多条规则单调叠加但永不超过1；
 * 权重wᵢ来自{@link RuleWeightService}（由审核标注的精度推导 —— 误报多的规则
 * 说话声音变小）。
 * Noisy-OR treats each rule as independent evidence of fraud. Properties: a single
 * rule degenerates to w·s (matching the old behavior); multiple rules compound
 * monotonically without exceeding 1; weights come from measured precision via
 * {@link RuleWeightService}, so noisy rules speak more quietly.
 *
 * <p>等级阈值：≥0.8 HIGH，≥0.5 MEDIUM，≥0.2 LOW，否则NORMAL —— 与各规则
 * 自身的打分习惯对齐（HIGH规则打0.9–1.0，MEDIUM规则打0.6–0.7）。
 * Level thresholds (≥0.8 HIGH, ≥0.5 MEDIUM, ≥0.2 LOW) align with how the rules
 * already score themselves.
 */
@Service
public class RiskDetectionEngine {

    private static final double HIGH_THRESHOLD = 0.8;
    private static final double MEDIUM_THRESHOLD = 0.5;
    private static final double LOW_THRESHOLD = 0.2;

    private final List<RiskRule> rules;
    private final MeterRegistry meterRegistry;
    private final RuleWeightService ruleWeights;
    private final LogisticModelService logisticModel;
    private final RuleConfigService ruleConfig;

    // Spring auto-injects every @Component that implements RiskRule.
    // Adding a new rule class is enough — no wiring change needed here.
    public RiskDetectionEngine(List<RiskRule> rules, MeterRegistry meterRegistry,
                               RuleWeightService ruleWeights, LogisticModelService logisticModel,
                               RuleConfigService ruleConfig) {
        this.rules = rules;
        this.meterRegistry = meterRegistry;
        this.ruleWeights = ruleWeights;
        this.logisticModel = logisticModel;
        this.ruleConfig = ruleConfig;
    }

    public RiskResult evaluate(Order order) {
        Timer.Sample sample = Timer.start(meterRegistry);

        // 运行时被禁用的规则直接跳过 —— 风控负责人可即时关掉一条太吵的规则
        // Skip rules disabled at runtime, so a fraud lead can silence a noisy rule live
        List<RiskResult> triggered = rules.stream()
                .filter(rule -> ruleConfig.isEnabled(rule.getRuleName()))
                .map(rule -> rule.evaluate(order))
                .filter(r -> r.getRiskScore() > 0)
                .collect(Collectors.toList());

        RiskResult result = combine(order, triggered);

        sample.stop(meterRegistry.timer("fraudshield.risk.evaluation.duration"));
        meterRegistry.counter("fraudshield.risk.events.total",
                "riskLevel", result.getRiskLevel().name()).increment();
        return result;
    }

    private RiskResult combine(Order order, List<RiskResult> triggered) {
        if (triggered.isEmpty()) {
            return RiskResult.builder()
                    .orderId(order.getOrderId())
                    .riskLevel(RiskLevel.NORMAL)
                    .riskScore(0.0)
                    .triggeredRules(List.of())
                    .explanation("No rules triggered")
                    .build();
        }

        double survivorProbability = 1.0;
        List<String> allRules = new ArrayList<>();
        List<String> explanations = new ArrayList<>();

        for (RiskResult r : triggered) {
            // 每条命中规则的名字在其triggeredRules里（规则各自报告自己）。手动权重覆盖优先，
            // 否则用精度自动推导的权重。/ Each rule reports itself; a manual weight override
            // wins, otherwise the precision-derived auto weight.
            double weight = r.getTriggeredRules().stream()
                    .mapToDouble(name -> {
                        Double override = ruleConfig.weightOverride(name);
                        return override != null ? override : ruleWeights.weightFor(name);
                    })
                    .min()
                    .orElse(1.0);
            survivorProbability *= 1.0 - Math.min(1.0, weight * r.getRiskScore());
            allRules.addAll(r.getTriggeredRules());
            explanations.add(r.getExplanation());
        }
        double combined = Math.round((1.0 - survivorProbability) * 100.0) / 100.0;

        // 逻辑回归就位时与noisy-OR各占一半：启发式兜底防少样本模型跑偏，
        // 模型纠正启发式学不到的组合效应。模型未训练时保持纯noisy-OR行为。
        // When the logistic model is trained, blend 50/50 with noisy-OR: the
        // heuristic floors a model trained on few labels, the model corrects
        // combination effects the heuristic can't express. Untrained = pure noisy-OR.
        var learned = logisticModel.predict(allRules);
        if (learned.isPresent()) {
            double ml = learned.getAsDouble();
            combined = Math.round((0.5 * combined + 0.5 * ml) * 100.0) / 100.0;
            explanations.add(String.format(
                    "ML fraud probability %.2f (trained on %d labeled orders)",
                    ml, logisticModel.trainedOnSamples()));
        }

        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(levelFor(combined))
                .riskScore(combined)
                .triggeredRules(allRules)
                .explanation(String.join("; ", explanations))
                .build();
    }

    private RiskLevel levelFor(double score) {
        if (score >= HIGH_THRESHOLD) {
            return RiskLevel.HIGH;
        }
        if (score >= MEDIUM_THRESHOLD) {
            return RiskLevel.MEDIUM;
        }
        if (score >= LOW_THRESHOLD) {
            return RiskLevel.LOW;
        }
        return RiskLevel.NORMAL;
    }
}
