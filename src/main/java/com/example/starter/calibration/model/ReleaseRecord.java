package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行；
 * measurementVersionNo 固化放行当时的测量版本号，使已放行快照在重算后仍可追溯。
 *
 * @param id                   放行记录 ID（自增）
 * @param batchId              放行批次 ID（UUID），同一批原子放行共享
 * @param measurementId        测量记录 ID
 * @param measurementVersionNo 放行时固化的测量版本号
 * @param releasedBy           放行人（X-Actor-Id）
 * @param releasedAt           放行时间（UTC）
 */
public record ReleaseRecord(
        long id,
        String batchId,
        long measurementId,
        int measurementVersionNo,
        String releasedBy,
        Instant releasedAt) {
}
