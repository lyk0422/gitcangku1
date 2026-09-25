package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 待复核清单项：待放行且当前修订版本尚无有效 PASS 复核的测量。
 *
 * @param measurementKey 测量键
 * @param instrumentId   仪器 ID
 * @param revision       当前修订版本号
 * @param submittedBy    提交人（复核人须不同于此人）
 * @param measuredAt     测量时刻（UTC）
 * @param createdAt      提交时间（UTC）
 */
public record PendingReviewItem(
        String measurementKey,
        String instrumentId,
        int revision,
        String submittedBy,
        Instant measuredAt,
        Instant createdAt) {
}
