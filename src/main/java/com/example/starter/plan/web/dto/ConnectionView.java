package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续登记视图（含基于当前生效计划的实时评估）。所有时刻 UTC，区间左闭右开；
 * 数量/时长字段同时给出要求值、实际值与差额。已取消接续的评估仍按历史占用计算。
 */
public record ConnectionView(
        long id,
        String predecessorScheduleKey,
        String successorScheduleKey,
        String transferSectionId,
        int guaranteeMinutes,
        boolean crossMidnight,
        String status,
        Instant predecessorArrivalUtc,
        Instant successorDepartureUtc,
        int requiredGapMinutes,
        long actualGapMinutes,
        long shortfallMinutes,
        Instant createdAt,
        Instant cancelledAt) {
}
