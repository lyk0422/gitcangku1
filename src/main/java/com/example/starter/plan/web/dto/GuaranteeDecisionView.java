package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 保障改签决策历史视图（只读，追加后不可变）。
 */
public record GuaranteeDecisionView(
        long id,
        String batchKey,
        String connectionKey,
        String predecessorScheduleKey,
        String successorScheduleKey,
        int predecessorVersion,
        Instant oldDepartureUtc,
        Instant newDepartureUtc,
        int shiftMinutes,
        long shortfallBeforeMinutes,
        Instant createdAt) {
}