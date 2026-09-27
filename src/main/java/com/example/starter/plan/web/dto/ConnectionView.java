package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续关系视图。时刻为 UTC；actualMinutes 为实际接续分钟数，
 * deficitMinutes 为保障差额（要求 - 实际，正值不足、负值富余），调用方无需自行推算。
 *
 * @param cancelledAt 取消时刻；接续生效中为 null
 */
public record ConnectionView(
        long id,
        String predecessorScheduleKey,
        String successorScheduleKey,
        int guaranteeMinutes,
        boolean crossMidnight,
        String status,
        Instant predecessorArrivalUtc,
        Instant successorDepartureUtc,
        long actualMinutes,
        long deficitMinutes,
        Instant createdAt,
        Instant cancelledAt) {
}
