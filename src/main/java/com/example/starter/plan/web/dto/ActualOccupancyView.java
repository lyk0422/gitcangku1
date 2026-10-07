package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用实际状态视图：计划时隙与实际进出时刻分列，区间左闭右开。
 */
public record ActualOccupancyView(
        int seq,
        String trainNo,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        String actualStatus,
        Instant enteredAt,
        Instant exitedAt,
        Integer exitPlanVersion) {
}
