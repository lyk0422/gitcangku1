package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 占用实际状态视图：计划时隙与实际轨迹分列；enteredAt/exitedAt 为服务端确认时刻（UTC），
 * 未发生对应事件时为 null。
 */
public record OccupancyActualView(
        int seq,
        String trainNo,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        String actualStatus,
        Instant enteredAt,
        Instant exitedAt) {
}
