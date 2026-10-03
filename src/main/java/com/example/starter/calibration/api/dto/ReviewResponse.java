package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核响应。携带当前法定人数统计：validAcceptCount/validRejectCount 为有效结论数，
 * requiredQuorum 为形成裁决所需的一致结论数。
 *
 * @param id                 复核 ID
 * @param outlierId          离群记录 ID
 * @param reviewer           复核人
 * @param decision           结论：ACCEPT / REJECT
 * @param reason             复核理由；未填写为 null
 * @param batchRevision      复核引用的测量修订版本
 * @param certificateId      复核引用的证书 ID
 * @param certificateVersion 复核引用的证书版本
 * @param status             复核状态：VALID / STALE
 * @param validAcceptCount   当前有效 ACCEPT 数（实际值）
 * @param validRejectCount   当前有效 REJECT 数（实际值）
 * @param requiredQuorum     裁决所需一致结论数（要求值）
 * @param outlierStatus      本次复核后的离群状态：PENDING / ACCEPTED / REJECTED
 * @param createdAt          复核时间（UTC）
 */
public record ReviewResponse(
        long id,
        long outlierId,
        String reviewer,
        String decision,
        String reason,
        int batchRevision,
        long certificateId,
        int certificateVersion,
        String status,
        int validAcceptCount,
        int validRejectCount,
        int requiredQuorum,
        String outlierStatus,
        Instant createdAt) {
}