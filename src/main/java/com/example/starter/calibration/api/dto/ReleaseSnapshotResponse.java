package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行快照响应（诊断用）。快照不可变，后续测量修订不改写。
 *
 * @param revision      放行时的批次修订版本
 * @param readingCount  放行时参与统计的读数条数（未排除）
 * @param excludedCount 放行时已排除读数条数
 * @param meanValue     放行时未排除读数均值（十进制字符串）；无读数为 null
 * @param releasedBy    放行人
 * @param releasedAt    放行时间（UTC）
 */
public record ReleaseSnapshotResponse(
        int revision,
        int readingCount,
        int excludedCount,
        String meanValue,
        String releasedBy,
        Instant releasedAt) {
}