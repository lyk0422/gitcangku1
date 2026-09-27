package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 离群复核记录（不可变）。stale=TRUE 表示提交时版本已变更或证书失效，
 * 该记录仅作留痕，不参与裁决计数。
 *
 * @param id            复核记录 ID（自增）
 * @param outlierId     离群记录 ID
 * @param requestId     复核幂等键
 * @param reviewer      复核人（必须不同于读数提交人）
 * @param decision      复核结论：ACCEPT 保留 / REJECT 排除
 * @param reason        复核理由
 * @param batchRevision 复核引用的批次修订版本号
 * @param certificateId 复核引用的证书版本（证书 ID）
 * @param stale         是否已失效（版本已变更或证书失效，410 留痕）
 * @param createdAt     复核时间（UTC）
 */
public record OutlierReview(
        long id,
        long outlierId,
        String requestId,
        String reviewer,
        ReviewDecision decision,
        String reason,
        int batchRevision,
        long certificateId,
        boolean stale,
        Instant createdAt) {
}
