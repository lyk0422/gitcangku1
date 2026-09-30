package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 离群复核记录，只增不改。引用的测量修订版本或证书版本失效时 {@code stale=true}，
 * STALE 复核不计入裁决，但作为历史证据保留。
 *
 * @param id                  复核记录 ID（自增）
 * @param outlierId           所属离群记录 ID
 * @param reviewer            复核人（不同于读数提交人）
 * @param decision            复核结论：ACCEPT 保留 / REJECT 排除
 * @param reason              复核理由（必填）
 * @param measurementRevision 复核引用的测量修订版本号
 * @param certificateId       复核引用的证书版本（测量绑定的证书 ID）
 * @param stale               是否因引用版本失效被标记 STALE
 * @param createdAt           复核时间（UTC）
 */
public record OutlierReview(
        long id,
        long outlierId,
        String reviewer,
        ReviewDecision decision,
        String reason,
        int measurementRevision,
        long certificateId,
        boolean stale,
        Instant createdAt) {
}
