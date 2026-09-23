package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 测量最新版本指针。每键唯一一行，与新版本行在同一事务内更新。
 *
 * @param measurementKey      业务测量键
 * @param latestRevision      最新修订版本号
 * @param latestMeasurementId 最新版本对应的 measurement.id
 * @param createdAt           首次提交时间（UTC）
 * @param updatedAt           最近一次修订时间（UTC）
 */
public record MeasurementHead(
        String measurementKey,
        int latestRevision,
        long latestMeasurementId,
        Instant createdAt,
        Instant updatedAt) {
}
