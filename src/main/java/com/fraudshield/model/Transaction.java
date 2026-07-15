package com.fraudshield.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 一笔资金划转 —— AML交易监控的基本单元（对应欺诈侧的Order）。
 * A money movement — the atom of AML transaction monitoring, the finance-domain
 * counterpart of the fraud side's {@link Order}. A transaction has two parties and a
 * direction, which is what makes laundering typologies (structuring, layering, mule
 * fan-in/out) meaningful in a way a single-party order never is.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Transaction {
    private String transactionId;
    private String senderAccount;    // originating account
    private String senderName;       // party name — screened against the watchlist
    private String receiverAccount;
    private String receiverName;
    private Double amount;
    private String currency;         // ISO code, e.g. "USD"
    private String originCountry;    // ISO-2, e.g. "US"
    private String destCountry;
    private String channel;          // WIRE / ACH / CARD / CRYPTO ...
    private LocalDateTime timestamp;
}
