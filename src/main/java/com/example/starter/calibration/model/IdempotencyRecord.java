package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等去重记录。全局 requestId 唯一；成功响应快照与业务写入同事务提交，失败请求不占键。
 *
 * @param requestId      全局请求 ID（幂等键）
 * @param operation      操作类型：FIT_SUBMIT / FIT_APPROVE
 * @param resource       操作资源标识（如仪器 ID、拟合 ID）
 * @param fingerprint    规范化参数指纹：十进制按值、文本原样，含期望版本
 * @param actor          操作者（非空）
 * @param responseStatus 首次响应 HTTP 状态码
 * @param responseBody   首次完整响应体（JSON）
 * @param createdAt      记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String operation,
        String resource,
        String fingerprint,
        String actor,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
