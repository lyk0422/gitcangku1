package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行快照视图（不可变）。后续测量修订不改写旧快照。
 *
 * @param id            快照 ID
 * @param batchRevision 放行的测量修订版本
 * @param includedCount 放行时纳入统计的读数条数
 * @param excludedCount 放行时已排除的读数条数
 * @param meanValue     放行时纳入读数均值（十进制字符串）；无纳入读数为 null
 * @param minValue      放行时纳入读数最小值（十进制字符串）；无纳入读数为 null
 * @param maxValue      放行时纳入读数最大值（十进制字符串）；无纳入读数为 null
 * @param releasedBy    放行人
 * @param releasedAt    放行时间（UTC）
 */
public record SnapshotView(
        long id,
        int batchRevision,
        int includedCount,
        int excludedCount,
        String meanValue,
        String minValue,
        String maxValue,
        String releasedBy,
        Instant releasedAt) {
}