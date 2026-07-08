package com.fraudshield.service;

import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.TreeSet;

/**
 * 逻辑回归评分层 — 从审核标注学习"哪些规则组合真的意味着欺诈"
 * Logistic-regression scorer: learns which rule combinations actually mean fraud,
 * trained on human review labels.
 *
 * <p>与noisy-OR权重层的分工：权重层调节单条规则的音量（精度差的说话小声），
 * 但仍假设规则独立。逻辑回归学的是**组合**效应 —— 比如"规则A单独命中多为误报，
 * 但A+B同时命中几乎全是欺诈"这类交互，独立性假设永远学不到截距和相对权重。
 * Division of labor vs. the noisy-OR weight layer: weights tune each rule's volume
 * but still assume independence. Logistic regression learns the combined picture —
 * intercept and relative coefficients that independence can never express.
 *
 * <p>特征与标签 (features & labels): one binary feature per rule (fired / not fired);
 * label = CONFIRMED_FRAUD → 1, FALSE_POSITIVE / APPROVED → 0. 特征就是规则位向量，
 * 所以模型天然可解释：系数正大 = 强欺诈信号，系数负 = 误报倾向。
 *
 * <p>训练时机 (training): 每5分钟后台重训一次；不足{@value #MIN_SAMPLES}条标注
 * 或只有单一类别时不产出模型（{@link #predict}返回empty，引擎退回纯noisy-OR）——
 * 冷启动安全，模型只在有据可依时才发言。
 * Retrained every 5 minutes in the background. With fewer than {@value #MIN_SAMPLES}
 * labels, or labels of only one class, no model is produced and the engine falls
 * back to pure noisy-OR — the model only speaks when the data supports it.
 *
 * <p>为什么手写批量梯度下降而不是引一个ML库：特征维度=规则数（个位数），
 * 样本量=人工标注数（至多几千）—— 这个规模下一个依赖库的成本远高于30行数学。
 * Why hand-rolled batch gradient descent instead of an ML library: feature dimension
 * = rule count (single digits), samples = human labels (thousands at most). At that
 * scale a dependency costs more than thirty lines of math.
 */
@Service
public class LogisticModelService {

    private static final Logger log = LoggerFactory.getLogger(LogisticModelService.class);

    static final int MIN_SAMPLES = 10;
    private static final int EPOCHS = 300;
    private static final double LEARNING_RATE = 0.5;
    private static final double L2_LAMBDA = 0.01;

    private final RiskEventRepository repository;

    // volatile整体替换：预测热路径无锁读 / volatile swap keeps the predict path lock-free
    private volatile Model model;

    public LogisticModelService(RiskEventRepository repository) {
        this.repository = repository;
    }

    /** 已训练模型对该规则组合的欺诈概率；无模型时empty / fraud probability, or empty when untrained. */
    public OptionalDouble predict(List<String> firedRules) {
        Model m = model;
        if (m == null) {
            return OptionalDouble.empty();
        }
        double z = m.bias;
        for (int i = 0; i < m.ruleNames.length; i++) {
            if (firedRules.contains(m.ruleNames[i])) {
                z += m.weights[i];
            }
        }
        return OptionalDouble.of(sigmoid(z));
    }

    /** 训练样本数（0 = 无模型）/ samples behind the current model, 0 when untrained. */
    public int trainedOnSamples() {
        Model m = model;
        return m == null ? 0 : m.samples;
    }

    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void retrain() {
        try {
            trainFromLabels();
        } catch (Exception e) {
            // 训练失败沿用旧模型 —— 评分可用性优先 / keep serving the old model on failure
            log.warn("Logistic model retraining failed, keeping previous model: {}", e.getMessage());
        }
    }

    void trainFromLabels() {
        List<boolean[]> features = new ArrayList<>();
        List<Integer> labels = new ArrayList<>();
        TreeSet<String> ruleSet = new TreeSet<>();

        List<RiskEvent> events = repository.findAll();
        for (RiskEvent e : events) {
            Integer label = labelOf(e.getReviewStatus());
            if (label == null || e.getTriggeredRules() == null || e.getTriggeredRules().isBlank()) {
                continue;
            }
            for (String r : e.getTriggeredRules().split(",")) {
                ruleSet.add(r.trim());
            }
        }
        String[] ruleNames = ruleSet.toArray(new String[0]);

        for (RiskEvent e : events) {
            Integer label = labelOf(e.getReviewStatus());
            if (label == null || e.getTriggeredRules() == null || e.getTriggeredRules().isBlank()) {
                continue;
            }
            boolean[] x = new boolean[ruleNames.length];
            for (String r : e.getTriggeredRules().split(",")) {
                int idx = Arrays.binarySearch(ruleNames, r.trim());
                if (idx >= 0) {
                    x[idx] = true;
                }
            }
            features.add(x);
            labels.add(label);
        }

        long positives = labels.stream().filter(l -> l == 1).count();
        if (labels.size() < MIN_SAMPLES || positives == 0 || positives == labels.size()) {
            // 数据不足或单一类别：模型闭嘴 / not enough evidence - the model stays silent
            model = null;
            log.debug("Logistic model not trained: {} samples, {} positives", labels.size(), positives);
            return;
        }

        // ── 批量梯度下降 / batch gradient descent on logistic loss + L2 ─────────
        double[] w = new double[ruleNames.length];
        double bias = 0.0;
        int n = labels.size();

        for (int epoch = 0; epoch < EPOCHS; epoch++) {
            double[] gradW = new double[w.length];
            double gradB = 0.0;
            for (int s = 0; s < n; s++) {
                double z = bias;
                boolean[] x = features.get(s);
                for (int i = 0; i < w.length; i++) {
                    if (x[i]) {
                        z += w[i];
                    }
                }
                double error = sigmoid(z) - labels.get(s);
                gradB += error;
                for (int i = 0; i < w.length; i++) {
                    if (x[i]) {
                        gradW[i] += error;
                    }
                }
            }
            bias -= LEARNING_RATE * gradB / n;
            for (int i = 0; i < w.length; i++) {
                w[i] -= LEARNING_RATE * (gradW[i] / n + L2_LAMBDA * w[i]);
            }
        }

        model = new Model(ruleNames, w, bias, n);
        log.info("Logistic model trained on {} labeled events, rules={}, weights={}",
                n, Arrays.toString(ruleNames), Arrays.toString(round(w)));
    }

    private Integer labelOf(String reviewStatus) {
        if ("CONFIRMED_FRAUD".equals(reviewStatus)) {
            return 1;
        }
        // APPROVED与FALSE_POSITIVE都是"最终放行" = 非欺诈标签
        // Both mean the order was ultimately allowed - the non-fraud label
        if ("FALSE_POSITIVE".equals(reviewStatus) || "APPROVED".equals(reviewStatus)) {
            return 0;
        }
        return null; // pending/legacy rows carry no label
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static double[] round(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = Math.round(values[i] * 100.0) / 100.0;
        }
        return out;
    }

    private record Model(String[] ruleNames, double[] weights, double bias, int samples) {
    }
}
