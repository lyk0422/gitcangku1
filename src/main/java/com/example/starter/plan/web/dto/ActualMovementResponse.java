package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 实际进入 / 退出确认响应。serverUtc 为服务端确认时刻（UTC），planVersion 为事件发生时原计划版本。
 */
public record ActualMovementResponse(
        String scheduleKey,
        int seq,
        String trainNo,
        String sectionId,
        String actualStatus,
        Instant serverUtc,
        int planVersion) {
}
