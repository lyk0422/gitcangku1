package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 批量保障改签决策证据视图（不可变历史）。所有时刻 UTC；
 * 未发生改签的条目（REJECTED）计划与时刻字段为 null。
 */
public record ConnectionDecisionView(
        long id,
        String batchRequestKey,
        String successorScheduleKey,
        String oldSuccessorScheduleKey,
        String newSuccessorScheduleKey,
        Long connectionId,
        String predecessorScheduleKey,
        Integer successorBaseVersion,
        Instant oldDepartureUtc,
        Instant newDepartureUtc,
        Instant predecessorArrivalUtc,
        Integer guaranteeMinutes,
        Integer requiredGapMinutes,
        Long actualGapMinutes,
        Long shortfallMinutes,
        String itemResult,
        String reasonCode,
        Instant createdAt) {
}
