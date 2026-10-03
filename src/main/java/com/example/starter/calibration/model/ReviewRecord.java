package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 复核记录。不可变；STALE 表示引用的版本已变更或证书已失效，不计入裁决。
 *
 * @param id                 复核记录 ID（自增）
 * @param outlierId          离群记录 ID
 * @param reviewer           复核人，须不同于读数提交人
 * @param decision           复核结论：ACCEPT / REJECT
 * @param reason             复核理由；ACCEPT 必填，REJECT 可选；未填为 null
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateId      复核引用的证书 ID
 * @param certificateVersion 复核引用的证书版本
 * @param status             状态：VALID 有效 / STALE 已过期
 * @param createdAt          复核时间（UTC）
 */
public record ReviewRecord(
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