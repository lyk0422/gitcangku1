package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续风险判定证据视图（不可变历史）。所有时刻 UTC；
 * 无候选改签时间窗时 window/newSuccessorDeparture 相关字段为 null。
 */
public record ConnectionRiskView(
        long id,
        long connectionId,
        String predecessorScheduleKey,
        String successorScheduleKey,
        String predecessorOldScheduleKey,
        String predecessorNewScheduleKey,
        int predecessorBaseVersion,
        int predecessorRescheduledVersion,
        Instant oldPredArrivalUtc,
        Instant newPredArrivalUtc,
        Instant oldSuccessorDepartureUtc,
        Instant newSuccessorDepartureUtc,
        int guaranteeMinutes,
        int requiredGapMinutes,
        long actualGapMinutes,
        long shortfallMinutes,
        Instant candidateWindowStartUtc,
        Instant candidateWindowEndUtc,
        boolean candidateAvailable,
        boolean successorStarted,
        Instant createdAt) {
}
