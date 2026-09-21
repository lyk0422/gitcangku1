package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行。
 *
 * @param id            放行记录 ID（自增）
 * @param batchId       放行批次 ID（UUID），同一批原子放行共享
 * @param measurementId 测量记录 ID
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 */
public record ReleaseRecord(
        long id,
        String batchId,
        long measurementId,
        String releasedBy,
        Instant releasedAt) {
}
