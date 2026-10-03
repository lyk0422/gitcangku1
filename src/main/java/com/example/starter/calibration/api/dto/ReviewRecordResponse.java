package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核记录响应（诊断用）。
 *
 * @param id                 复核记录 ID
 * @param reviewer           复核人
 * @param decision           复核结论：ACCEPT / REJECT
 * @param reason             复核理由；未填为 null
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateVersion 复核引用的证书版本
 * @param status             复核状态：VALID / STALE
 * @param createdAt          复核时间（UTC）
 */
public record ReviewRecordResponse(
        long id,
        String reviewer,
        String decision,
        String reason,
        int batchRevision,
        int certificateVersion,
        String status,
        Instant createdAt) {
}