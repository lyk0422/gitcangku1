package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变联合裁决记录的持久化视图：对应 bundle_arbitration 表的一行，含簇级裁决前/后快照 JSON 原文。
 *
 * @param requestId        联合裁决请求标识（全局幂等键）
 * @param bundleKey        所属关联簇唯一标识
 * @param operator         执行裁决的审核员标识
 * @param beforeSnapshotJson 裁决前簇级快照（JSON 原文）
 * @param afterSnapshotJson  裁决后簇级快照（JSON 原文）
 * @param arbitratedAtUtc  裁决完成时刻（UTC）
 */
public record ArbitrationRecordView(
        String requestId,
        String bundleKey,
        String operator,
        String beforeSnapshotJson,
        String afterSnapshotJson,
        Instant arbitratedAtUtc) {
}
