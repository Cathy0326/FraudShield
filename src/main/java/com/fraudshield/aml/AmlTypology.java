package com.fraudshield.aml;

import com.fraudshield.dto.AmlSignal;
import com.fraudshield.model.Transaction;

/**
 * 一条AML典型手法检测器 —— 对应欺诈侧的RiskRule。新增一个@Component实现即自动加入引擎。
 * An AML typology detector — the finance-side analogue of RiskRule. Adding a new
 * @Component implementation is enough; the engine auto-discovers it (same pattern as
 * the fraud rules).
 */
public interface AmlTypology {

    /** 评估交易；未命中返回score=0的信号 / evaluate a transaction; a miss returns score 0. */
    AmlSignal evaluate(Transaction txn);

    default String getName() {
        return this.getClass().getSimpleName();
    }
}
