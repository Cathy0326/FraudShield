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
}
