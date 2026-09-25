package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 跨仪器联合放行批次头记录。记录创建后不可变。
 *
 * @param id           联合放行批次自增主键
 * @param jointBatchKey 业务联合批次键，全局唯一（幂等键）
 * @param requestId    请求幂等键；同键同参重放返回首次响应快照
 * @param releasedBy   放行人（X-Actor-Id）
 * @param releasedAt   放行提交时刻（UTC）
 * @param snapshotKeys 成功时固化的测量键列表（字典序 JSON 数组文本）
 * @param createdAt    记录创建时间（UTC）
 */
public record JointReleaseBatch(
        long id,
        String jointBatchKey,
        String requestId,
        String releasedBy,
        Instant releasedAt,
        String snapshotKeys,
        Instant createdAt) {
}
