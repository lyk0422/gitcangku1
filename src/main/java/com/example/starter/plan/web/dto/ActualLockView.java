package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 区段当前实际锁视图（某占用处于 INSIDE）。
 */
public record ActualLockView(
        String scheduleKey,
        int occupancySeq,
        String trainNo,
        String sectionId,
        Instant enteredAt) {
}
