package com.fraudshield.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HourlyStatDTO {
    private String hour;        // e.g. "2026-06-14 10:00"
    private Long orderCount;
    private Long riskCount;
}
