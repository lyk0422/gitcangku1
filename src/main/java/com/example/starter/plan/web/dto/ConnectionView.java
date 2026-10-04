package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 接续视图。生效接续携带实时评估：requiredDepartureUtc 为要求的后车最早始发（前车终到加保障分钟数），
 * actualDepartureUtc 为实际后车始发，slackMinutes 为富余分钟数（负值表示保障不足）。
 * 已取消接续不再评估，三个评估字段为 null。
 */
public record ConnectionView(
        String connectionKey,
        String predecessorScheduleKey,
        String successorScheduleKey,
        int guaranteeMinutes,
        boolean crossMidnight,
        String status,
        int registeredPredecessorVersion,
        Instant requiredDepartureUtc,
        Instant actualDepartureUtc,
        Long slackMinutes) {
}