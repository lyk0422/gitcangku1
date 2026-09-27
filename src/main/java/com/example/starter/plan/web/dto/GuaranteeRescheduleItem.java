package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 批量保障改签单计划结果：记录原/新始发时刻与实际平移分钟数。
 */
public record GuaranteeRescheduleItem(
        String scheduleKey,
        int version,
        Instant previousDepartureUtc,
        Instant newDepartureUtc,
        long shiftMinutes,
        String riskStatus) {
}
