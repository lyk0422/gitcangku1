package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 限速重排记录视图（不可变历史证据）。createdAt 为 UTC 毫秒时间戳；
 * 各时刻字段为 UTC 瞬时，区间左闭右开。
 */
public record RearrangeRecordView(
        String scheduleKey,
        String restrictionKey,
        int restrictionVersion,
        String sectionId,
        int maxSpeedKmh,
        Instant restrictionStartUtc,
        Instant restrictionEndUtc,
        int seq,
        String trainNo,
        Instant originalStartUtc,
        Instant originalEndUtc,
        Instant shiftedStartUtc,
        Instant shiftedEndUtc,
        int shiftMinutes,
        int affectedMinutes,
        long createdAt) {
}