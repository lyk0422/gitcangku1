package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核响应。携带本次复核结果与当前法定人数进度（实际值/要求值），调用方无需猜测。
 *
 * @param id             复核记录 ID
 * @param outlierId      离群记录 ID
 * @param reviewer       复核人
 * @param decision       复核结论：ACCEPT / REJECT
 * @param reason         复核理由；未填为 null
 * @param status         复核状态：EFFECTIVE / STALE
 * @param batchRevision  复核引用的批次测量修订版本
 * @param certificateId  复核引用的证书 ID（证书版本）
 * @param acceptCount    当前有效 ACCEPT 票数（实际值）
 * @param rejectCount    当前有效 REJECT 票数（实际值）
 * @param requiredQuorum 形成裁决所需的相同结论票数（要求值，恒为 2）
 * @param verdict        已形成的有效裁决：ACCEPT/REJECT；未裁决为 null
 * @param outlierStatus  离群记录状态：PENDING / ACCEPTED / REJECTED
 * @param createdAt      复核时间（UTC）
 */
public record ReviewResponse(
        long id,
        long outlierId,
        String reviewer,
        String decision,
        String reason,
        String status,
        int batchRevision,
        long certificateId,
        int acceptCount,
        int rejectCount,
        int requiredQuorum,
        String verdict,
        String outlierStatus,
        Instant createdAt) {
}
