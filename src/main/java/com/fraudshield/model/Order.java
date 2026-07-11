package com.fraudshield.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Order {
    private String orderId;
    private String userId;
    private Double amount;
    private String ipAddress;
    private String deviceId;
    private LocalDateTime timestamp;

    // 收货/账单地址 —— 地址模式检测的输入（代收点/转运骡子、账单≠收货不符）
    // Shipping/billing address — inputs for address-pattern detection (drop
    // addresses / reshipping mules, and billing≠shipping mismatch). Nullable:
    // orders predating these fields, and callers that don't supply them, leave them null.
    private String shippingAddress;
    private String billingAddress;

    /**
     * 兼容旧调用点的6参构造器 —— 地址字段加入前的构造调用保持不变，地址默认null。
     * Backward-compatible 6-arg constructor: callers written before the address
     * fields keep compiling unchanged, with both addresses defaulting to null.
     */
    public Order(String orderId, String userId, Double amount, String ipAddress,
                 String deviceId, LocalDateTime timestamp) {
        this(orderId, userId, amount, ipAddress, deviceId, timestamp, null, null);
    }
}
