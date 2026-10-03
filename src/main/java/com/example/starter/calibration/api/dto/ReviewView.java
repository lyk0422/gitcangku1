package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核明细视图。
 *
 * @param id                 复核 ID
 * @param reviewer           复核人
 * @param decision           结论：ACCEPT / REJECT
 * @param reason             复核理由；未填写为 null
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateId      复核引用的证书 ID
 * @param certificateVersion 复核引用的证书版本
 * @param status             复核状态：VALID / STALE
 * @param createdAt          复核时间（UTC）
 */
public record ReviewView(
        long id,
        String reviewer,
        String decision,
        String reason,
        int batchRevision,
        long certificateId,
        int certificateVersion,
        String status,
        Instant createdAt) {
}