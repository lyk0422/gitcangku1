package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 离群复核。每条离群记录须至少两名不同于读数提交人的复核人给出一致结论才形成有效裁决；
 * 复核引用提交时的测量修订版本与证书版本，版本变更或证书失效时落库为 STALE。
 *
 * @param id                 复核 ID（自增）
 * @param outlierId          离群记录 ID
 * @param reviewer           复核人
 * @param decision           结论：ACCEPT 保留 / REJECT 排除
 * @param reason             复核理由；ACCEPT 必填，REJECT 可空（null 表示未填写）
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateId      复核引用的证书 ID
 * @param certificateVersion 复核引用的证书版本
 * @param status             状态：VALID 有效 / STALE 已失效
 * @param createdAt          复核时间（UTC）
 */
public record OutlierReview(
        long id,
        long outlierId,
        String reviewer,
        ReviewDecision decision,
        String reason,
        int batchRevision,
        long certificateId,
        int certificateVersion,
        ReviewStatus status,
        Instant createdAt) {
}