package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续风险判定历史视图（只读，追加后不可变）。
 * marked 为 false 表示判定保障不足但因后车已开始或非已发布而未自动标记。
 */
public record ConnectionRiskView(
        long id,
        String connectionKey,
        String predecessorScheduleKey,
        String successorScheduleKey,
        int predecessorVersion,
        Instant oldArrivalUtc,
        Instant newArrivalUtc,
        Instant oldDepartureUtc,
        Instant newDepartureUtc,
        int guaranteeMinutes,
        long actualMinutes,
        long shortfallMinutes,
        Instant windowStartUtc,
        Instant windowEndUtc,
        boolean marked,
        Instant createdAt) {
}