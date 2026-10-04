package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 单条接续的实时诊断评估：requiredDepartureUtc 要求值、actualDepartureUtc 实际值、
 * slackMinutes 富余分钟数（负值即保障差额）、satisfied 当前是否满足保障。
 */
public record ConnectionEvaluationView(
        String connectionKey,
        String role,
        String otherScheduleKey,
        int guaranteeMinutes,
        Instant requiredDepartureUtc,
        Instant actualDepartureUtc,
        long slackMinutes,
        boolean satisfied) {
}