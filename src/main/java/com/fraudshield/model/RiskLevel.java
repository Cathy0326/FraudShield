package com.fraudshield.model;

// Ordinal matters: higher ordinal = higher severity.
// RiskDetectionEngine uses ordinal comparison to pick the worst result when scores tie.
public enum RiskLevel {
    NORMAL,  // 0 — no signal
    LOW,     // 1 — minor anomaly
    MEDIUM,  // 2 — worth reviewing
    HIGH     // 3 — block / flag immediately
}
