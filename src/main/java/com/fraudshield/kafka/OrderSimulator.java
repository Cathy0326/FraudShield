package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 持续订单模拟器 — 统计上"像真的"的后台流量源
 * Continuous order simulator: a background traffic source that is statistically
 * plausible, not just noisy.
 *
 * <p>建模选择（也是这类模拟器的标准算法组合）：
 * The modeling choices — the standard algorithm stack for transaction simulators:
 *
 * <ol>
 * <li><b>Zipf用户活跃度</b>：200个虚拟用户按幂律选择（指数0.5）——少数重度买家、
 *     长尾偶发买家。此前只有5个用户/5个IP承载全部流量，每个IP每5分钟约10单，
 *     把FrequentIpRule（限5）永久压爆 —— 正常流量天天"11 orders in 5 minutes"。
 *     Zipf-distributed user activity (exponent 0.5 over 200 users): a few heavy
 *     shoppers, a long tail of occasional ones. The old pool of 5 users/5 IPs put
 *     ~10 orders per IP per 5-minute window - permanently over FrequentIpRule's
 *     limit of 5, so "normal" traffic was flagged nonstop. With 200 users the
 *     heaviest one carries ~3.7% of normal traffic ≈ 2 orders/window: organic
 *     velocity flags become rare-but-possible (a realistic false-positive feed
 *     for the review queue) instead of constant.</li>
 * <li><b>稳定身份图</b>：每个虚拟用户固定绑定自己的IP和设备 —— 这是图传播/
 *     关联账号功能有意义的前提；随机换IP的用户不构成可分析的图。
 *     Stable identity graph: each user keeps one IP and one device across orders.
 *     Linked-accounts and graph-risk features are only meaningful over stable
 *     identities.</li>
 * <li><b>对数正态金额</b>：amount = 用户均值 × e^(0.55·N(0,1))，右偏长尾 ——
 *     偶发的合法大额（约3%超均值3倍）会天然触发少量AbnormalAmountRule复核。
 *     Log-normal amounts around a per-user mean: right-skewed like real spend;
 *     ~3% of orders legitimately spike past 3x the mean, organically feeding
 *     AbnormalAmountRule with review-worthy cases.</li>
 * <li><b>非齐次泊松到达</b>：每个tick发Poisson(λ(t))个事件，λ(t)按60分钟正弦
 *     周期在0.4–1.4波动 —— 流量有节奏有突发，dashboard趋势线是波浪而不是直线。
 *     Non-homogeneous Poisson arrivals: each tick emits Poisson(λ(t)) events with
 *     λ(t) cycling 0.4-1.4 over 60 minutes, so traffic is bursty and wave-shaped
 *     (a full daily cycle would be invisible in a demo session).</li>
 * </ol>
 *
 * <p>攻击场景占比校准过，使每个场景可靠触发**自己的**规则而不误触别人的：
 * 攻击IP速率见各分支注释。速度突发场景改为每次3单连发 —— 之前单发3%的占比
 * 其实到不了频率阈值，"velocity burst"从未真正burst。
 * Attack-scenario shares are calibrated so each reliably trips ITS OWN rule
 * without cross-firing others (per-IP rates in the branch comments). The velocity
 * burst now sends 3 orders at once - at its old 3% single-order share it never
 * actually exceeded the frequency threshold.
 *
 * <p>默认关闭：SIMULATOR_ENABLED=true开启（compose里默认开，方便本地demo；
 * 真实部署绝不该有合成流量，所以代码默认false）。
 * Off by default in code — enable with SIMULATOR_ENABLED=true. docker-compose
 * enables it for local demos; a real deployment must never emit synthetic traffic.
 */
@Component
@ConditionalOnProperty(name = "fraudshield.simulator.enabled", havingValue = "true")
public class OrderSimulator {

    private static final Logger log = LoggerFactory.getLogger(OrderSimulator.class);

    private static final int NORMAL_USER_COUNT = 200;
    private static final double ZIPF_EXPONENT = 0.5;
    private static final double AMOUNT_SIGMA = 0.55;   // log-normal spread
    private static final long RATE_CYCLE_MS = 3_600_000; // 60-min traffic wave

    // 转运骡子/代收点地址 —— 多个假身份的订单都发往这里，命中AddressPatternRule
    // Reshipping-mule / drop-point address — many fake identities all ship here,
    // tripping AddressPatternRule
    private static final String MULE_ADDRESS = "1 Reship Way, Newark NJ 07101";

    // 黑名单IP池：轮换避免任一IP在FrequentIpRule窗口里堆积 —— 黑名单命中不该顺带
    // 刷成velocity。三个都由DataInitializer种入blacklist:ips。
    // Blacklisted-IP pool: rotate so no single one accumulates in FrequentIpRule's
    // window — a blacklist hit shouldn't incidentally also trip velocity. All three
    // are seeded into blacklist:ips by DataInitializer.
    private static final List<String> BLACKLIST_IPS =
            List.of("10.0.0.1", "10.0.0.2", "10.0.0.3");

    // 金额异常场景的用户池：都由DataInitializer种子化为"老账号 + 低历史均值($50)"。
    // 老账号 → 不误触HighAmountNewUserRule；低均值 → 尖峰命中AbnormalAmountRule。
    // 轮换让每个用户下单稀疏，EMA不会很快追平尖峰，规则持续可见。
    // Amount-spike user pool: each seeded by DataInitializer as an OLD account with a
    // low historical average ($50). Old account → won't mis-trip HighAmountNewUserRule;
    // low average → spikes trip AbnormalAmountRule. Rotating keeps each user's orders
    // sparse so its EMA doesn't quickly catch up to the spikes and stop firing.
    private static final List<String> SPIKE_USERS = List.of(
            "USER-TEST-002", "USER-SPIKE-1", "USER-SPIKE-2", "USER-SPIKE-3", "USER-SPIKE-4");

    /** 虚拟正常用户：固定的IP/设备/地址/消费水平 / synthetic user with stable identity. */
    record SimUser(String userId, String ip, String deviceId, String address, double meanAmount) { }

    private final OrderEventProducer producer;
    private final Random random = new Random();
    private final List<SimUser> normalUsers = new ArrayList<>(NORMAL_USER_COUNT);
    private final double[] zipfCumulative = new double[NORMAL_USER_COUNT];

    public OrderSimulator(OrderEventProducer producer) {
        this.producer = producer;

        // 用户群按索引确定性生成：消费水平用index的SplitMix64散列（无随机对象，
        // 天然可复现）——重启后同一用户保持同一IP/设备/消费水平，图特征和用户画像
        // 跨重启保持连贯。散列而非线性映射，避免消费水平与Zipf排名相关。
        // Population is deterministic by construction: each user's spend level is a
        // SplitMix64 hash of its index (no RNG object, reproducible across restarts),
        // so identities and spend survive restarts and graph/profile features stay
        // coherent. Hashing (not a linear map) decorrelates spend from Zipf rank.
        double cumulative = 0;
        for (int i = 0; i < NORMAL_USER_COUNT; i++) {
            normalUsers.add(new SimUser(
                    String.format("USER-SIM-%03d", i),
                    "203.0.113." + i,                      // TEST-NET-3, unique per user
                    String.format("DEV-SIM-%03d", i),
                    (100 + i) + " Main St, Springfield",   // stable home address, unique per user
                    15.0 + hashToUnit(i) * 105.0));        // personal mean $15-$120
            cumulative += 1.0 / Math.pow(i + 1.0, ZIPF_EXPONENT);
            zipfCumulative[i] = cumulative;
        }

        log.info("OrderSimulator ENABLED - NHPP arrivals (lambda 0.4-1.4 per 4s tick, 60-min "
                + "cycle), {} Zipf-distributed normal users, log-normal amounts", NORMAL_USER_COUNT);
    }

    @Scheduled(fixedRate = 4_000, initialDelay = 15_000)
    public void emitOrder() {
        // 每tick发Poisson(λ(t))个事件 —— 聚合即为泊松过程；一个事件可能是一批订单（突发场景）
        // Poisson(λ(t)) events per tick aggregates to a Poisson process; one event
        // may carry several orders (the burst scenario)
        int events = poisson(currentRate());
        for (int i = 0; i < events; i++) {
            nextOrders().forEach(producer::sendOrder);
        }
    }

    /**
     * 流量构成 — 74%正常，其余按场景校准（速率注释按均值λ=0.9、每分钟约13.5个事件计算）
     * Traffic mix: 74% normal, the rest calibrated per scenario (rate notes assume
     * the mean λ of 0.9 → ~13.5 events/min).
     */
    List<Order> nextOrders() {
        int roll = random.nextInt(100);
        if (roll < 8) {
            // 黑名单命中：金额低于100不触发新账号规则；IP从黑名单池轮换防velocity堆积
            // Blacklist hit: amount <100 so it won't touch the new-user rule; IP rotates
            // across the blacklist pool so BlacklistRule fires without piling up velocity
            return List.of(order("USER-DRIFTER-" + random.nextInt(3),
                    20.0 + random.nextInt(60), pick(BLACKLIST_IPS)));
        }
        if (roll < 12) {
            // 新账号大额（USER-TEST-001种子：账龄2小时）；IP轮换 → 只亮HighAmountNewUserRule
            // Young account, high amount (USER-TEST-001 seeded 2h old); rotating IP so it
            // lights ONLY HighAmountNewUserRule, never velocity
            return List.of(order("USER-TEST-001", 120.0 + random.nextInt(200),
                    rotatingAttackIp()));
        }
        if (roll < 16) {
            // 金额异常：从种子化的老账号池轮换选用户，IP轮换 → 只亮AbnormalAmountRule
            // Amount spike: rotate over the seeded old-account pool and rotate the IP,
            // so it lights ONLY AbnormalAmountRule (not new-user, not velocity).
            //
            // 关键：这些用户大多数下单贴着$50基线，只有约1/3是真正的尖峰。低额单不断把EMA
            // 拉回基线，尖峰才能持续>3x均值。若每单都是高额，EMA几单内就追平尖峰、规则熄火——
            // 这正是之前的毛病。
            // Key: these users MOSTLY order near their $50 baseline; only ~1/3 are real
            // spikes. The frequent low orders keep pulling the EMA back down to baseline,
            // so a spike stays above 3x the average. If every order were high, the EMA
            // would catch up within a few orders and the rule would go silent — which is
            // exactly the wrinkle this fixes.
            boolean spike = random.nextInt(3) == 0;
            double amount = spike
                    ? 200.0 + random.nextInt(160)   // >3x $50 → trips AbnormalAmountRule
                    : 35.0 + random.nextInt(35);     // ~baseline, holds the EMA down
            return List.of(order(pick(SPIKE_USERS), amount, rotatingAttackIp()));
        }
        if (roll < 20) {
            // 卡测试攻击：同IP+同设备发小额单，每单换假身份；4% → 10分钟窗口~5.4单 ≥4阈值
            // Card testing: micro-charges, fresh fake identity each, one IP+device;
            // 4% → ~5.4 orders per 10-min window, above CardTestingRule's threshold
            return List.of(order("USER-JD-" + random.nextInt(12), 0.5 + random.nextInt(9),
                    "66.66.66.66", "DEV-EMU-666"));
        }
        if (roll < 23) {
            // 速度突发：一次连发3单 —— 3%×3单 → ~1.2单/分 → 窗口~6单，真正超过限5
            // Velocity burst: 3 orders at once; 3%×3 → ~6 orders/window, genuinely
            // over FrequentIpRule's limit (single orders at 3% never got there)
            List<Order> burst = new ArrayList<>(3);
            for (int i = 0; i < 3; i++) {
                burst.add(order("USER-BURST-" + random.nextInt(2),
                        15.0 + random.nextInt(30), "77.77.77.77"));
            }
            return burst;
        }
        if (roll < 26) {
            // 代收点/转运骡子：不同假身份的订单发往同一收货地址，账单地址各不相同（AVS不符）
            // 真实货物 → 金额偏高；不同IP，唯一交汇点是收货地址 → 命中AddressPatternRule
            // Drop address / reshipping mule: orders under different fake identities all
            // ship to one address with differing billing addresses (AVS mismatch). Real
            // goods → higher amounts; different IPs, the only convergence is the shipping
            // address → trips AddressPatternRule
            String muleUser = "USER-MULE-" + random.nextInt(8);
            return List.of(new Order(uid(), muleUser, 150.0 + random.nextInt(600),
                    "45.45.45." + random.nextInt(200), "DEV-MULE-0" + random.nextInt(6),
                    LocalDateTime.now(), MULE_ADDRESS,
                    muleUser + " billing, " + random.nextInt(9000) + " Card Rd"));
        }
        // 正常订单：Zipf选用户，对数正态金额，用户自带固定IP/设备/地址，账单=收货
        // Normal order: Zipf-picked user, log-normal amount, the user's own stable
        // IP/device/address, billing == shipping (no AVS mismatch)
        SimUser user = pickZipfUser();
        double amount = clamp(user.meanAmount() * Math.exp(AMOUNT_SIGMA * random.nextGaussian()),
                8.0, 400.0);
        return List.of(new Order(uid(), user.userId(), round2(amount),
                user.ip(), user.deviceId(), LocalDateTime.now(),
                user.address(), user.address()));
    }

    // ── NHPP internals ───────────────────────────────────────────────────────

    /**
     * 当前每tick事件率：60分钟正弦周期在[0.4, 1.4]间波动（均值0.9）。
     * Current events-per-tick rate: [0.4, 1.4] over a 60-minute sine cycle (mean 0.9).
     */
    double currentRate() {
        double phase = 2.0 * Math.PI * (System.currentTimeMillis() % RATE_CYCLE_MS)
                / (double) RATE_CYCLE_MS;
        return 0.4 + 0.5 * (1.0 + Math.sin(phase));
    }

    /** Knuth泊松采样 — λ小时最简单正确的算法 / Knuth's sampler, correct and simple for small λ. */
    int poisson(double lambda) {
        double threshold = Math.exp(-lambda);
        double p = 1.0;
        int k = 0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > threshold);
        return k - 1;
    }

    /**
     * Zipf选择：预计算的累积权重上二分 —— O(log n)每次抽样。
     * Zipf pick via binary search over precomputed cumulative weights, O(log n) per draw.
     */
    private SimUser pickZipfUser() {
        double target = random.nextDouble() * zipfCumulative[NORMAL_USER_COUNT - 1];
        int lo = 0;
        int hi = NORMAL_USER_COUNT - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (zipfCumulative[mid] < target) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return normalUsers.get(lo);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Order order(String userId, double amount, String ip) {
        // 攻击场景的设备从小池子随机 —— 攻击者不在乎设备指纹一致性
        // Attack scenarios draw devices from a small pool; attackers don't keep
        // device fingerprints consistent
        return order(userId, amount, ip, "DEV-GEN-0" + random.nextInt(5));
    }

    private Order order(String userId, double amount, String ip, String deviceId) {
        return new Order(uid(), userId, round2(amount), ip, deviceId, LocalDateTime.now());
    }

    /**
     * 轮换攻击IP（TEST-NET-2，198.51.100.10–249）。金额类场景的规则与IP无关 ——
     * 摊到240个地址后，任一IP的命中率都远低于FrequentIpRule阈值，杜绝velocity交叉误触。
     * Rotating attack IP from TEST-NET-2 (198.51.100.10–249). The amount-based
     * scenarios' rules don't depend on IP, so spreading across 240 addresses keeps
     * every IP far below FrequentIpRule's threshold — no velocity cross-fire.
     */
    private String rotatingAttackIp() {
        return "198.51.100." + (10 + random.nextInt(240));
    }

    private String pick(List<String> pool) {
        return pool.get(random.nextInt(pool.size()));
    }

    private String uid() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * index的确定性散列 → [0,1)。SplitMix64终结器：位混合充分，无需随机对象。
     * Deterministic hash of an index into [0,1) via the SplitMix64 finalizer —
     * well-distributed bit-mixing with no RNG object to seed or store.
     */
    private static double hashToUnit(long x) {
        x = (x ^ (x >>> 30)) * 0xbf58476d1ce4e5b9L;
        x = (x ^ (x >>> 27)) * 0x94d049bb133111ebL;
        x = x ^ (x >>> 31);
        return (x >>> 11) * 0x1.0p-53;   // top 53 bits → [0,1)
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
