package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行后复核记录。每个批次至多一条成功复核；reviewKey 全局唯一。
 *
 * @param id         复核记录 ID（自增）
 * @param batchId    被复核的放行批次 ID
 * @param reviewKey  复核幂等键，全局唯一
 * @param reviewer   复核人（X-Actor-Id），不得为该批原放行人
 * @param snapshot   冻结的整批版本快照（JSON 文本）
 * @param reviewedAt 复核时间（UTC）
 */
public record BatchReview(
        long id,
        String batchId,
        String reviewKey,
        String reviewer,
        String snapshot,
        Instant reviewedAt) {
}
