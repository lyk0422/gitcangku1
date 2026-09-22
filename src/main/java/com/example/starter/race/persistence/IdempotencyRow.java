package com.example.starter.race.persistence;

/**
 * idempotency_record 表行记录：成功写请求的原响应，供同键重放。
 *
 * @param requestId      全局唯一请求ID
 * @param operation      操作类型
 * @param requestDigest  请求参数规范化摘要（SHA-256，十六进制）
 * @param responseStatus 原成功响应HTTP状态码
 * @param responseBody   原成功响应体JSON
 * @param createdAt      首次成功提交时间，Unix毫秒时间戳
 */
public record IdempotencyRow(
        String requestId,
        String operation,
        String requestDigest,
        int responseStatus,
        String responseBody,
        long createdAt
) {
}
