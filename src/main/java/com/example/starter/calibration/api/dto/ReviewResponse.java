package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 离群复核响应。outlierStatus 为本次复核后的离群记录裁决状态。
 *
 * @param id            复核记录 ID
 * @param outlierId     离群记录 ID
 * @param requestId     复核幂等键
 * @param reviewer      复核人
 * @param decision      复核结论：ACCEPT / REJECT
 * @param reason        复核理由
 * @param batchRevision 复核引用的批次修订版本号
 * @param certificateId 复核引用的证书版本
 * @param stale         是否已失效（410 留痕记录为 true）
 * @param outlierStatus 本次复核后的离群记录裁决状态：PENDING / ACCEPTED / REJECTED
 * @param createdAt     复核时间（UTC）
 */
public record ReviewResponse(
        long id,
        long outlierId,
        String requestId,
        String reviewer,
        String decision,
        String reason,
        int batchRevision,
        long certificateId,
        boolean stale,
        String outlierStatus,
        Instant createdAt) {
}
