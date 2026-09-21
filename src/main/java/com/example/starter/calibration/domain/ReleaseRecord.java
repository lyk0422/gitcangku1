package com.example.starter.calibration.domain;

import java.time.Instant;

/**
 * 放行历史记录。一旦写入永久保留，证书撤销不影响历史本身。
 *
 * @param id            放行记录主键 ID
 * @param measurementId 被放行的测量 ID（唯一，一次测量最多放行一次）
 * @param certificateId 放行时使用的证书 ID
 * @param releasedBy    放行人
 * @param releasedAt    放行时刻（UTC）
 * @param batchId       所属放行批次 ID
 */
public record ReleaseRecord(
        long id,
        long measurementId,
        long certificateId,
        String releasedBy,
        Instant releasedAt,
        String batchId) {
}
