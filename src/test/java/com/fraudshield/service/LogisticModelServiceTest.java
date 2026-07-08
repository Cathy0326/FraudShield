package com.fraudshield.service;

import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LogisticModelServiceTest {

    @Mock RiskEventRepository repository;

    private LogisticModelService service;

    @BeforeEach
    void setUp() {
        service = new LogisticModelService(repository);
    }

    private RiskEvent labeled(String rules, String status) {
        return RiskEvent.builder()
                .orderId("ORD-" + System.identityHashCode(new Object()))
                .riskLevel("HIGH")
                .triggeredRules(rules)
                .reviewStatus(status)
                .build();
    }

    /** 可分数据集：FraudRule→全是欺诈，NoisyRule→全是误报 / cleanly separable dataset. */
    private List<RiskEvent> separableDataset() {
        List<RiskEvent> events = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            events.add(labeled("FraudRule", "CONFIRMED_FRAUD"));
            events.add(labeled("NoisyRule", "FALSE_POSITIVE"));
        }
        return events;
    }

    @Test
    void untrained_predictsEmpty() {
        assertThat(service.predict(List.of("AnyRule"))).isEmpty();
        assertThat(service.trainedOnSamples()).isZero();
    }

    @Test
    void separableData_learnsWhichRuleMeansFraud() {
        when(repository.findAll()).thenReturn(separableDataset());

        service.trainFromLabels();

        double fraudP = service.predict(List.of("FraudRule")).orElseThrow();
        double noiseP = service.predict(List.of("NoisyRule")).orElseThrow();
        assertThat(fraudP).isGreaterThan(0.7);
        assertThat(noiseP).isLessThan(0.3);
        assertThat(service.trainedOnSamples()).isEqualTo(16);
    }

    @Test
    void combinationEffect_bothRulesTogether_scoresAboveEitherAlone() {
        // 组合数据：单独命中各半对半，同时命中全是欺诈 —— 独立性假设学不到的模式
        // A+B together is always fraud even though each alone is 50/50 - the
        // pattern independence assumptions can never learn
        List<RiskEvent> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            events.add(labeled("RuleA", "CONFIRMED_FRAUD"));
            events.add(labeled("RuleA", "FALSE_POSITIVE"));
            events.add(labeled("RuleB", "CONFIRMED_FRAUD"));
            events.add(labeled("RuleB", "FALSE_POSITIVE"));
            events.add(labeled("RuleA,RuleB", "CONFIRMED_FRAUD"));
        }
        when(repository.findAll()).thenReturn(events);

        service.trainFromLabels();

        double both = service.predict(List.of("RuleA", "RuleB")).orElseThrow();
        double aloneA = service.predict(List.of("RuleA")).orElseThrow();
        assertThat(both).isGreaterThan(aloneA);
    }

    @Test
    void tooFewLabels_staysSilent() {
        when(repository.findAll()).thenReturn(List.of(
                labeled("RuleA", "CONFIRMED_FRAUD"),
                labeled("RuleB", "FALSE_POSITIVE")));

        service.trainFromLabels();

        assertThat(service.predict(List.of("RuleA"))).isEmpty();
    }

    @Test
    void singleClassLabels_staysSilent() {
        // 全是欺诈标注 → 没有对照组，学不出边界 / all one class = nothing to separate
        List<RiskEvent> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            events.add(labeled("RuleA", "CONFIRMED_FRAUD"));
        }
        when(repository.findAll()).thenReturn(events);

        service.trainFromLabels();

        assertThat(service.predict(List.of("RuleA"))).isEmpty();
    }

    @Test
    void pendingAndLegacyRows_areExcludedFromTraining() {
        List<RiskEvent> events = separableDataset();
        events.add(labeled("FraudRule", "PENDING_REVIEW"));
        events.add(labeled("FraudRule", null));
        when(repository.findAll()).thenReturn(events);

        service.trainFromLabels();

        // 只有16条有标注 / only the 16 decided rows count
        assertThat(service.trainedOnSamples()).isEqualTo(16);
    }
}
