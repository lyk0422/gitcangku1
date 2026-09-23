package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行批次。一次批量放行/重新放行生成一个批次；复核驳回后原子置 REVIEW_REQUIRED 并冻结版本快照。
 *
 * @param batchId    批次 ID（UUID）
 * @param releasedBy 放行人（X-Actor-Id）；复核人不得与放行人相同
 * @param status     批次状态：RELEASED 已放行 / REVIEW_REQUIRED 复核驳回待重新放行
 * @param createdAt  批次创建时间（UTC）
 * @param reviewedAt 复核驳回时间（UTC）；未复核为 null
 */
public record ReleaseBatch(
        String batchId,
        String releasedBy,
        BatchStatus status,
        Instant createdAt,
        Instant reviewedAt) {
}
