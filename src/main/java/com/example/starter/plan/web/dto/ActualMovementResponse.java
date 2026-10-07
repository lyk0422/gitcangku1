package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 实际进入/驶出响应。时刻均为服务端 UTC；未发生的时刻字段为 null。
 */
public record ActualMovementResponse(
        String scheduleKey,
        int occupancySeq,
        String trainNo,
        String sectionId,
        String actualStatus,
        Instant enteredAt,
        Instant exitedAt,
        Integer exitPlanVersion,
        int planVersion) {
}
