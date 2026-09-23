package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行；重新放行时未驳回位置复用原测量记录。
 *
 * @param id            放行记录 ID（自增）
 * @param batchId       放行批次 ID（UUID），同一批原子放行共享
 * @param position      批次内位置（从 1 开始，按提交顺序）；重新放行须逐位置精确映射
 * @param measurementId 测量记录 ID
 * @param releasedBy    放行人（X-Actor-Id）
 * @param releasedAt    放行时间（UTC）
 */
public record ReleaseRecord(
        long id,
        String batchId,
        int position,
        long measurementId,
        String releasedBy,
        Instant releasedAt) {
}
