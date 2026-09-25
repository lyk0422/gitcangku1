package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 乘务资质风险记录视图（不可变）。
 */
public record RiskRecordView(
        String crewId,
        String role,
        String qualificationCode,
        String reason,
        Instant createdAtUtc) {
}
