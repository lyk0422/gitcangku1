package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 放行批次。首次批量放行与复核后的重新放行各产生一个批次；批次行本身只流转状态，内容不可变。
 *
 * @param batchId            放行批次 ID（UUID）
 * @param releasedBy         放行人（X-Actor-Id）
 * @param releasedAt         放行时间（UTC）
 * @param status             状态：RELEASED 生效中 / REVIEW_REQUIRED 复核驳回待处理 / SUPERSEDED 已被取代
 * @param requestId          重新放行幂等键；首次批量放行为 null
 * @param requestFingerprint 重新放行请求参数规范化指纹（SHA-256）；首次批量放行为 null
 * @param sourceBatchId      重新放行来源批次 ID；首次批量放行为 null
 */
public record ReleaseBatch(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        BatchStatus status,
        String requestId,
        String requestFingerprint,
        String sourceBatchId) {
}
