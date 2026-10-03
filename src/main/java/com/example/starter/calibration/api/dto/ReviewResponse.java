package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核响应。status 为 VALID 或 STALE；裁决形成时携带裁决结论。
 *
 * @param id                   复核记录 ID
 * @param outlierId            离群记录 ID
 * @param reviewer             复核人
 * @param decision             复核结论：ACCEPT / REJECT
 * @param reason               复核理由；未填为 null
 * @param batchRevision        复核引用的测量修订版本
 * @param certificateVersion   复核引用的证书版本
 * @param status               复核状态：VALID / STALE
 * @param outlierStatus        复核后离群记录状态：PENDING / ACCEPTED / REJECTED
 * @param adjudicationDecision 裁决结论；未形成裁决为 null
 * @param createdAt            复核时间（UTC）
 */
public record ReviewResponse(
        long id,
        long outlierId,
        String reviewer,
        String decision,
        String reason,
        int batchRevision,
        int certificateVersion,
        String status,
        String outlierStatus,
        String adjudicationDecision,
        Instant createdAt) {
}