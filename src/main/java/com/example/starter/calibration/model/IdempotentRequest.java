package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求快照。仅成功提交的请求会落库；失败不占键。
 *
 * @param requestId    客户端幂等键（X-Request-Id）
 * @param scope        操作作用域，如 RELEASE、BUDGET:M-1
 * @param actor        操作者（X-Actor-Id）
 * @param operationType 操作类型
 * @param fingerprint  请求指纹（SHA-256 十六进制）
 * @param statusCode   首次成功响应的 HTTP 状态码
 * @param responseBody 首次成功响应体 JSON 快照
 * @param createdAt    首次成功提交时间（UTC）
 */
public record IdempotentRequest(
        String requestId,
        String scope,
        String actor,
        String operationType,
        String fingerprint,
        int statusCode,
        String responseBody,
        Instant createdAt) {
}
