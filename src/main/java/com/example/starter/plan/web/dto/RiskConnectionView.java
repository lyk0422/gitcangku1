package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 风险后车的一条生效接续诊断：当前保障差额与最近一次风险判定时生成的可选改签时间窗。
 * windowStartUtc/windowEndUtc 取自最新 RISK 事件，无 RISK 事件时为 null。
 */
public record RiskConnectionView(long connectionId, String predecessorKey,
                                 int requiredMinutes, long actualMinutes, long shortfallMinutes,
                                 Instant windowStartUtc, Instant windowEndUtc) {
}