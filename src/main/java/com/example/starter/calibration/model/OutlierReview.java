package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 复核记录。每次复核（含版本失效的 STALE 复核）追加不可变记录，封存引用的修订与证书版本。
 *
 * @param id            复核记录 ID（自增）
 * @param outlierId     离群记录 ID
 * @param reviewer      复核人，须不同于读数提交人
 * @param decision      复核结论：ACCEPT/REJECT
 * @param reason        复核理由；ACCEPT 必填，REJECT 可选；未填为 null
 * @param batchRevision 复核引用的批次测量修订版本
 * @param certificateId 复核引用的证书 ID（证书版本）
 * @param status        状态：EFFECTIVE 有效计入法定人数 / STALE 版本失效不计入
 * @param createdAt     复核时间（UTC）
 */
public record OutlierReview(
        long id,
        long outlierId,
        String reviewer,
        ReviewDecision decision,
        String reason,
        int batchRevision,
        long certificateId,
        ReviewStatus status,
        Instant createdAt) {
}
