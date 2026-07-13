package com.fraudshield.service;

import com.fraudshield.model.RiskEvent;
import com.fraudshield.repository.RiskEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 历史数据回填 — 仅用于开发/演示。
 * Dev/demo-only backfill of past-dated risk events.
 *
 * <p>为什么需要它：正常流量（模拟器/真实订单）的 detectedAt 永远是"现在"，所以时间范围
 * 报表、逐时趋势、周环比精度箭头、规则×小时热力图在一个新起的实例上全都无数据可展示。
 * 本服务直接写入带**过去时间戳**的合成事件，让这些历史向功能立刻"活"起来；同时也是验证
 * 持久化是否真的落库（重启后仍在=Postgres成功，消失=还在用内存H2）的最简办法。
 * Why this exists: live traffic (simulator or real orders) is always stamped "now",
 * so date-range reports, hourly trends, week-over-week precision arrows, and the
 * rule×hour heatmap have nothing to show on a fresh instance. This writes synthetic
 * events with PAST timestamps so every history-oriented feature lights up — and it's
 * the simplest way to prove persistence: seed, restart, and check the data survived
 * (still there = Postgres worked; gone = still on in-memory H2).
 *
 * <p>刻意做的事：(1) 每条规则给不同的目标精度，让"规则健康看板"有强弱之分；(2) 近7天与
 * 之前7天的精度不同，让周环比箭头真的有升有降；(3) 昼夜节律的流量，让趋势线像真的。
 * Deliberate touches: (1) each rule gets a different target precision so the Rule
 * Health Board shows real spread; (2) the last-7-days precision differs from the prior
 * 7 days so the WoW arrows actually move; (3) diurnal volume so the trend line looks
 * real. Reviewed events set reviewStatus/reviewedBy/reviewedAt but are NOT appended to
 * the HMAC audit chain — that chain must reflect real decisions made through the app,
 * not backfilled fiction.
 */
@Service
public class HistoricalSeedService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalSeedService.class);
    private static final String SEED_PREFIX = "HIST-";   // marks synthetic rows for clean re-seeding

    private final RiskEventRepository repository;

    public HistoricalSeedService(RiskEventRepository repository) {
        this.repository = repository;
    }

    // rule, prior-7d precision, current-7d precision, mean amount, relative volume
    private record RuleSpec(String rule, double priorPrec, double curPrec, double meanAmount, double weight) { }

    private static final List<RuleSpec> RULES = List.of(
            new RuleSpec("BlacklistRule",         0.95, 0.95,  60, 0.9),  // healthy, flat
            new RuleSpec("CardTestingRule",       0.70, 0.57,   6, 1.3),  // decaying ▼, high volume
            new RuleSpec("FrequentIpRule",        0.42, 0.34,  40, 1.1),  // noisy
            new RuleSpec("AbnormalAmountRule",    0.55, 0.63, 260, 1.0),  // improving ▲
            new RuleSpec("HighAmountNewUserRule", 0.64, 0.55, 220, 0.8),  // slipping ▼
            new RuleSpec("AddressPatternRule",    0.74, 0.88, 380, 0.7)); // improving ▲, big tickets

    /**
     * 回填过去 days 天的合成事件（1..30）。先清除上次的合成数据，避免重复堆叠。
     * Backfill synthetic events across the past {@code days} days (clamped 1..30).
     * Prior synthetic rows are cleared first so repeated calls don't stack.
     *
     * @return number of events written
     */
    public synchronized int seed(int days) {
        int span = Math.max(1, Math.min(days, 30));

        List<RiskEvent> prior = repository.findAll().stream()
                .filter(e -> e.getOrderId() != null && e.getOrderId().startsWith(SEED_PREFIX))
                .toList();
        repository.deleteAll(prior);

        Random rnd = new Random(7);                        // deterministic → reproducible demos
        LocalDateTime now = LocalDateTime.now();
        double totalWeight = RULES.stream().mapToDouble(RuleSpec::weight).sum();
        List<RiskEvent> batch = new ArrayList<>();
        int seq = 0;

        for (int d = span - 1; d >= 0; d--) {
            for (int h = 0; h < 24; h++) {
                // 昼夜节律：凌晨低谷、午后/傍晚高峰 / diurnal: overnight trough, afternoon peak
                double diurnal = 0.4 + 0.6 * Math.max(0.0, Math.sin(Math.PI * (h - 6) / 14.0));
                int n = (int) Math.round(diurnal * (2 + rnd.nextInt(4)));
                for (int k = 0; k < n; k++) {
                    RuleSpec spec = pick(rnd, totalWeight);
                    LocalDateTime ts = now.minusDays(d)
                            .withHour(h).withMinute(rnd.nextInt(60)).withSecond(rnd.nextInt(60)).withNano(0);
                    if (ts.isAfter(now)) {
                        continue;   // don't create future-dated rows for the current partial hour
                    }
                    boolean recent = d < 7;
                    double targetPrec = recent ? spec.curPrec() : spec.priorPrec();
                    double amount = Math.max(1.0, spec.meanAmount() * Math.exp(0.5 * rnd.nextGaussian()));
                    double score = 0.45 + rnd.nextDouble() * 0.5;

                    RiskEvent.RiskEventBuilder b = RiskEvent.builder()
                            .orderId(SEED_PREFIX + String.format("%05d", seq++))
                            .userId("USER-H" + (1000 + rnd.nextInt(400)))
                            .ipAddress("198.51.100." + (1 + rnd.nextInt(250)))
                            .deviceId("DEV-H" + rnd.nextInt(200))
                            .amount(round2(amount))
                            .riskLevel(score >= 0.8 ? "HIGH" : "MEDIUM")
                            .riskScore(round2(score))
                            .triggeredRules(spec.rule())
                            .explanation(spec.rule() + " triggered (synthetic history)")
                            .detectedAt(ts);

                    // ~70% 已审：按目标精度决定 CONFIRMED_FRAUD，其余分到 FP/APPROVED
                    // ~70% reviewed: CONFIRMED_FRAUD at the target precision, rest split FP/APPROVED
                    if (rnd.nextDouble() < 0.70) {
                        String decision = rnd.nextDouble() < targetPrec ? "CONFIRMED_FRAUD"
                                : rnd.nextBoolean() ? "FALSE_POSITIVE" : "APPROVED";
                        LocalDateTime reviewedAt = ts.plusMinutes(15 + rnd.nextInt(240));
                        if (reviewedAt.isAfter(now)) {
                            reviewedAt = now;
                        }
                        b.reviewStatus(decision)
                         .reviewedBy(rnd.nextBoolean() ? "operator" : "admin")
                         .reviewedAt(reviewedAt)
                         .reviewNotes("backfilled");
                    }
                    // else: left PENDING_REVIEW (builder default) → also populates the review queue

                    batch.add(b.build());
                }
            }
        }

        repository.saveAll(batch);
        log.info("HistoricalSeedService: wrote {} synthetic events across {} days (cleared {} prior)",
                batch.size(), span, prior.size());
        return batch.size();
    }

    private RuleSpec pick(Random rnd, double total) {
        double r = rnd.nextDouble() * total;
        for (RuleSpec s : RULES) {
            r -= s.weight();
            if (r <= 0) {
                return s;
            }
        }
        return RULES.get(RULES.size() - 1);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
