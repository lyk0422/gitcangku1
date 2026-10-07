package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 区段当前实际锁视图（一条 INSIDE 占用）。enteredAt 为服务端确认的实际进入时刻（UTC）。
 */
public record ActualLockView(
        String scheduleKey,
        int seq,
        String trainNo,
        String sectionId,
        Instant enteredAt) {
}
