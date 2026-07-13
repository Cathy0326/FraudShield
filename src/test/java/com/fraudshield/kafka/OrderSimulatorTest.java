package com.fraudshield.kafka;

import com.fraudshield.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OrderSimulatorTest {

    @Mock OrderEventProducer producer;

    private OrderSimulator simulator;

    @BeforeEach
    void setUp() {
        simulator = new OrderSimulator(producer);
    }

    private List<Order> sample(int minOrders) {
        List<Order> orders = new ArrayList<>();
        while (orders.size() < minOrders) {
            orders.addAll(simulator.nextOrders());
        }
        return orders;
    }

    @Test
    void everyOrder_hasAllRequiredFields() {
        for (Order o : sample(200)) {
            assertThat(o.getOrderId()).startsWith("ORD-");
            assertThat(o.getUserId()).isNotBlank();
            assertThat(o.getAmount()).isPositive();
            assertThat(o.getIpAddress()).isNotBlank();
            assertThat(o.getDeviceId()).isNotBlank();
            assertThat(o.getTimestamp()).isNotNull();
        }
    }

    @Test
    void orderIds_areUnique() {
        List<Order> orders = sample(500);

        assertThat(orders.stream().map(Order::getOrderId).distinct().count())
                .isEqualTo(orders.size());
    }

    @Test
    void trafficMix_isMostlyNormalWithSomeRuleTrippers() {
        // 场景占比：正常77%，其余按各规则的触发阈值校准（见OrderSimulator注释）
        // ~77% normal; scenario shares are calibrated to each rule's threshold
        List<Order> orders = sample(600);

        long blacklistIp = orders.stream().filter(o -> o.getUserId().startsWith("USER-DRIFTER-")).count();
        long burst       = orders.stream().filter(o -> "77.77.77.77".equals(o.getIpAddress())).count();
        long youngAcct   = orders.stream().filter(o -> "USER-TEST-001".equals(o.getUserId())).count();
        long spike       = orders.stream().filter(o -> isSpikeUser(o.getUserId())).count();
        long cardTest    = orders.stream().filter(o -> "66.66.66.66".equals(o.getIpAddress())).count();
        long dropAddr    = orders.stream().filter(o -> o.getUserId().startsWith("USER-MULE-")).count();
        long suspicious  = blacklistIp + burst + youngAcct + spike + cardTest + dropAddr;

        // 可疑订单约27%（突发场景一次3单推高了订单占比）；宽容差防偶发失败
        // ~27% of ORDERS are suspicious (the 3-at-once burst inflates its share);
        // generous tolerance keeps the test stable
        assertThat(suspicious).isBetween((long) (600 * 0.15), (long) (600 * 0.42));
        // 每种场景都必须出现 —— 否则某条规则在demo里永远不亮
        // Every scenario must occur, or some rule never lights up in the demo
        assertThat(blacklistIp).isPositive();
        assertThat(burst).isPositive();
        assertThat(youngAcct).isPositive();
        assertThat(spike).isPositive();
        assertThat(cardTest).isPositive();
        assertThat(dropAddr).isPositive();
    }

    private static boolean isSpikeUser(String userId) {
        return "USER-TEST-002".equals(userId) || userId.startsWith("USER-SPIKE-");
    }

    @Test
    void amountScenarios_rotateIpsSoTheyCannotDominateTheVelocityWindow() {
        // 核心修复：金额类场景（新账号/金额异常）的IP必须轮换，任一IP都不能在
        // FrequentIpRule的5分钟窗口里堆积 —— 这正是"203.0.113.202 28单/5分钟"的病根。
        // The core fix: the amount-based scenarios (young-account / amount-spike) must
        // rotate IPs so no single one accumulates in FrequentIpRule's 5-minute window —
        // that was the root of the "203.0.113.202: 28 orders in 5 min" false velocity.
        List<Order> amountOrders = sample(4000).stream()
                .filter(o -> "USER-TEST-001".equals(o.getUserId()) || isSpikeUser(o.getUserId()))
                .collect(Collectors.toList());

        assertThat(amountOrders).isNotEmpty();
        // 都在专属攻击网段，绝不复用正常用户或黑名单IP / dedicated attack range only
        assertThat(amountOrders).allMatch(o -> o.getIpAddress().startsWith("198.51.100."));

        Map<String, Long> perIp = amountOrders.stream()
                .collect(Collectors.groupingBy(Order::getIpAddress, Collectors.counting()));
        // 摊开在大量IP上，且没有单点热点（任一IP占比 < 5%）
        // Spread across many IPs with no hotspot (no single IP holds >5% of them)
        assertThat(perIp.size()).isGreaterThan(30);
        long maxPerIp = perIp.values().stream().mapToLong(Long::longValue).max().orElse(0);
        assertThat((double) maxPerIp / amountOrders.size()).isLessThan(0.05);
    }

    @Test
    void spikeScenario_usesSeededOldAccountPool_notNewAccounts() {
        // 金额异常场景的用户必须来自种子化的老账号池，否则会误触HighAmountNewUserRule
        // Amount-spike users must come from the seeded old-account pool, or they'd
        // mis-trip HighAmountNewUserRule instead of AbnormalAmountRule
        List<Order> spike = sample(3000).stream()
                .filter(o -> isSpikeUser(o.getUserId()))
                .collect(Collectors.toList());

        assertThat(spike).isNotEmpty();
        // 轮换覆盖多个用户，避免单用户EMA很快追平尖峰 / rotation spans several users
        assertThat(spike.stream().map(Order::getUserId).distinct().count()).isGreaterThan(2);
        // 新行为：多数订单贴着$50基线（把EMA拉住），约1/3是真尖峰(>150)持续命中AbnormalAmountRule。
        // 若每单都高，EMA几单内就追平、规则熄火 —— 所以两类都必须出现。
        // New behavior: most orders sit near the $50 baseline (anchoring the EMA), ~1/3 are
        // genuine spikes (>150) that keep tripping AbnormalAmountRule. If every order were
        // high the EMA would catch up within a few orders and the rule would go quiet — so
        // BOTH kinds must appear.
        Map<Boolean, Long> byLevel = spike.stream()
                .collect(Collectors.partitioningBy(o -> o.getAmount() > 150.0, Collectors.counting()));
        assertThat(byLevel.get(true)).as("real spikes above 3x the $50 baseline").isGreaterThan(0L);
        assertThat(byLevel.get(false)).as("baseline orders holding the EMA down").isGreaterThan(0L);
        // 金额都落在该场景的边界内 / all amounts within the scenario's bounds
        assertThat(spike).allMatch(o -> o.getAmount() >= 35.0 && o.getAmount() < 360.0);
    }

    @Test
    void dropAddressScenario_manyIdentitiesShipToOneAddressWithBillingMismatch() {
        // 代收点场景：多个假身份的订单发往同一收货地址，账单地址各不同（AVS不符）
        // Drop-address scenario: many fake identities ship to one address, billing differs
        List<Order> mule = sample(3000).stream()
                .filter(o -> o.getUserId().startsWith("USER-MULE-"))
                .collect(Collectors.toList());

        assertThat(mule).isNotEmpty();
        // 所有骡子订单发往同一个收货地址 / all ship to the single mule address
        assertThat(mule.stream().map(Order::getShippingAddress).distinct()).hasSize(1);
        // 出现多个不同假身份 —— 这正是AddressPatternRule要抓的 / multiple distinct identities
        assertThat(mule.stream().map(Order::getUserId).distinct().count()).isGreaterThan(2);
        // 账单地址≠收货地址（AVS不符）/ billing differs from shipping (AVS mismatch)
        assertThat(mule).allMatch(o -> !o.getShippingAddress().equals(o.getBillingAddress()));
    }

    @Test
    void normalOrders_carryStableAddressWithBillingEqualShipping() {
        List<Order> normal = sample(1000).stream()
                .filter(o -> o.getUserId().startsWith("USER-SIM-"))
                .collect(Collectors.toList());

        // 正常订单账单=收货，不制造AVS噪声 / normal orders: billing == shipping, no AVS noise
        assertThat(normal).allMatch(o -> o.getShippingAddress() != null
                && o.getShippingAddress().equals(o.getBillingAddress()));
        // 每个用户地址稳定 / each user keeps one stable address
        Map<String, List<Order>> perUser = normal.stream()
                .collect(Collectors.groupingBy(Order::getUserId));
        perUser.forEach((user, os) ->
                assertThat(os.stream().map(Order::getShippingAddress).distinct()).hasSize(1));
    }

    @Test
    void velocityBurst_emitsMultipleOrdersAtOnce() {
        // 突发场景必须一次多单 —— 单发时它从未真正超过频率阈值
        // The burst must be multi-order; emitted singly it never crossed the limit
        for (int i = 0; i < 2000; i++) {
            List<Order> batch = simulator.nextOrders();
            if (!batch.isEmpty() && "77.77.77.77".equals(batch.get(0).getIpAddress())) {
                assertThat(batch).hasSize(3);
                assertThat(batch).allMatch(o -> "77.77.77.77".equals(o.getIpAddress()));
                return;
            }
        }
        throw new AssertionError("No velocity burst produced in 2000 draws");
    }

    @Test
    void normalTraffic_spreadsAcrossManyStableIdentities() {
        List<Order> normal = sample(2000).stream()
                .filter(o -> o.getUserId().startsWith("USER-SIM-"))
                .collect(Collectors.toList());

        // Zipf长尾：正常流量必须摊开在大量IP上 —— 5个IP扛全部流量正是之前
        // FrequentIpRule被正常流量刷屏的根因
        // The Zipf tail must spread traffic over many IPs; 5 IPs carrying everything
        // was exactly why FrequentIpRule fired on normal traffic nonstop
        Map<String, Long> perIp = normal.stream()
                .collect(Collectors.groupingBy(Order::getIpAddress, Collectors.counting()));
        assertThat(perIp.size()).isGreaterThan(50);

        // 最重度用户约占正常流量3.7% —— 上限放宽到10%仍足以证明没有单IP热点
        // Heaviest user carries ~3.7% of normal traffic; a 10% ceiling still proves
        // no single-IP hotspot exists
        long maxPerIp = perIp.values().stream().mapToLong(Long::longValue).max().orElse(0);
        assertThat((double) maxPerIp / normal.size()).isLessThan(0.10);

        // 身份稳定：同一用户的所有订单来自同一IP和设备
        // Identity stability: every order from a user shares that user's IP and device
        Map<String, List<Order>> perUser = normal.stream()
                .collect(Collectors.groupingBy(Order::getUserId));
        perUser.forEach((user, orders) -> {
            assertThat(orders.stream().map(Order::getIpAddress).distinct()).hasSize(1);
            assertThat(orders.stream().map(Order::getDeviceId).distinct()).hasSize(1);
        });
    }

    @Test
    void amounts_areLogNormalWithinClipBounds() {
        List<Order> normal = sample(1000).stream()
                .filter(o -> o.getUserId().startsWith("USER-SIM-"))
                .collect(Collectors.toList());

        assertThat(normal).allMatch(o -> o.getAmount() >= 8.0 && o.getAmount() <= 400.0);
        // 右偏分布：均值应高于中位数 / right-skewed: mean above median
        List<Double> sorted = normal.stream().map(Order::getAmount).sorted().collect(Collectors.toList());
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.get(sorted.size() / 2);
        assertThat(mean).isGreaterThan(median);
    }

    @Test
    void nhppRate_staysWithinConfiguredBand() {
        // λ(t) ∈ [0.4, 1.4]：泊松事件数非负，均值约等于λ
        // Rate stays in band; Poisson draws are non-negative with mean ≈ λ
        double rate = simulator.currentRate();
        assertThat(rate).isBetween(0.4, 1.4);

        int total = 0;
        for (int i = 0; i < 2000; i++) {
            int k = simulator.poisson(0.9);
            assertThat(k).isGreaterThanOrEqualTo(0);
            total += k;
        }
        assertThat(total / 2000.0).isBetween(0.7, 1.1);
    }
}
