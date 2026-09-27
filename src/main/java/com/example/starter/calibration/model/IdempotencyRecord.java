package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求记录。requestId 全局唯一；仅在操作成功时写入，失败不占键。
 *
 * @param requestId    幂等请求 ID
 * @param operation    操作类型：UPDATE_COMPONENTS / SET_THRESHOLD / RELEASE
 * @param actor        操作者（X-Actor-Id）
 * @param fingerprint  请求指纹（SHA-256）
 * @param statusCode   首次成功响应的 HTTP 状态码
 * @param responseBody 首次成功响应的完整 JSON 快照
 * @param createdAt    记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String operation,
        String actor,
        String fingerprint,
        int statusCode,
        String responseBody,
        Instant createdAt) {
}
