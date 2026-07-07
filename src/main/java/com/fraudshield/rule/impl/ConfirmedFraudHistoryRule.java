package com.fraudshield.rule.impl;

import com.fraudshield.model.Order;
import com.fraudshield.model.RiskLevel;
import com.fraudshield.model.RiskResult;
import com.fraudshield.repository.RiskEventRepository;
import com.fraudshield.rule.RiskRule;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 已确认欺诈历史规则 — 审核标注直接反哺检测引擎
 * Confirmed-fraud-history rule: review labels feeding straight back into detection.
 *
 * <p>其他规则只看当前这一单（金额、账龄、频率），本规则看的是**身份的历史**：
 * <ul>
 *   <li>该用户名下有人工确认的欺诈订单 → HIGH。惯犯的下一单不需要再触发任何
 *       金额/频率阈值，身份本身就是最强信号。</li>
 *   <li>该订单IP曾出现在**其他用户**被确认的欺诈订单里 → MEDIUM。欺诈团伙换账号
 *       不换基础设施 —— 新账号第一单就能被老账号的标注拦到。</li>
 * </ul>
 *
 * <p>Every other rule sees only the current order (amount, account age, velocity);
 * this one sees the identity's history:
 * <ul>
 *   <li>The user has a human-confirmed fraud order → HIGH. A repeat offender's next
 *       order shouldn't need to re-trip any amount/velocity threshold — the identity
 *       itself is the strongest signal.</li>
 *   <li>The order's IP appears in another user's confirmed-fraud history → MEDIUM.
 *       Fraud rings rotate accounts but reuse infrastructure, so a brand-new account's
 *       first order can be caught by labels earned on the old accounts.</li>
 * </ul>
 *
 * <p>与BlacklistRule的区别：黑名单是运营人员手工维护的静态清单；本规则的"名单"
 * 由审核决定自动生成，无需任何人手工同步。
 * Unlike BlacklistRule (a manually curated static list), this rule's "list" is generated
 * automatically by review decisions — nobody has to remember to sync it.
 */
@Component
public class ConfirmedFraudHistoryRule implements RiskRule {

    private static final String CONFIRMED_FRAUD = "CONFIRMED_FRAUD";

    private final RiskEventRepository repository;

    public ConfirmedFraudHistoryRule(RiskEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public RiskResult evaluate(Order order) {
        long priorFraud = countOrZero(order.getUserId());
        if (priorFraud > 0) {
            return result(order, RiskLevel.HIGH, 0.95,
                    "User " + order.getUserId() + " has " + priorFraud
                            + " confirmed fraud order(s) on record");
        }

        // 只有在用户自身干净时才查IP关联，省一次查询
        // Only check IP linkage when the user themselves is clean — saves a query
        if (order.getIpAddress() != null && repository.existsByIpAddressAndReviewStatusAndUserIdNot(
                order.getIpAddress(), CONFIRMED_FRAUD, order.getUserId())) {
            return result(order, RiskLevel.MEDIUM, 0.7,
                    "IP " + order.getIpAddress()
                            + " appears in another user's confirmed fraud history");
        }

        return result(order, RiskLevel.NORMAL, 0.0, "No confirmed fraud history");
    }

    private long countOrZero(String userId) {
        Long count = repository.countByUserIdAndReviewStatus(userId, CONFIRMED_FRAUD);
        return count == null ? 0L : count;
    }

    private RiskResult result(Order order, RiskLevel level, double score, String explanation) {
        return RiskResult.builder()
                .orderId(order.getOrderId())
                .riskLevel(level)
                .riskScore(score)
                .triggeredRules(level == RiskLevel.NORMAL ? List.of() : List.of(getRuleName()))
                .explanation(explanation)
                .build();
    }
}
