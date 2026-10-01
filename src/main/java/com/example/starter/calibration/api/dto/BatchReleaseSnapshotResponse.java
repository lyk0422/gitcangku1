package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 批次放行快照响应。快照不可改写，按生成顺序返回。
 *
 * @param id            快照 ID
 * @param revision      放行时测量修订版本
 * @param includedCount 放行时未排除读数数量
 * @param excludedCount 放行时已排除读数数量
 * @param meanValue     放行时未排除读数计算值均值（十进制字符串）；无未排除读数时为 null
 * @param releasedBy    放行人
 * @param releasedAt    放行时间（UTC）
 */
public record BatchReleaseSnapshotResponse(
        long id,
        int revision,
        int includedCount,
        int excludedCount,
        String meanValue,
        String releasedBy,
        Instant releasedAt) {
}