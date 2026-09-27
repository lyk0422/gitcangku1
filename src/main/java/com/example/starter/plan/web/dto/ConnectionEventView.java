package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续事件视图（历史业务证据，只增不改）。时刻为 UTC。
 * 可空字段语义稳定：previousSuccessorDepartureUtc 仅 RESCHEDULED 非空；
 * windowStartUtc/windowEndUtc 仅 RISK_MARKED 非空。
 */
public record ConnectionEventView(
        long id,
        String eventType,
        long connectionId,
        String predecessorScheduleKey,
        String successorScheduleKey,
        int predecessorVersion,
        Instant predecessorArrivalUtc,
        Instant successorDepartureUtc,
        Instant previousSuccessorDepartureUtc,
        int guaranteeMinutes,
        long actualMinutes,
        long deficitMinutes,
        Instant windowStartUtc,
        Instant windowEndUtc,
        Instant createdAt) {
}
