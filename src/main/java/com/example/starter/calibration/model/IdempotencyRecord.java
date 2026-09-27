package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求记录。requestId 唯一；失败请求随事务回滚不占键。
 *
 * @param requestId      幂等请求 ID
 * @param fingerprint    请求指纹（SHA-256 十六进制）
 * @param operation      操作类型
 * @param actor          操作者
 * @param responseStatus 首次成功响应 HTTP 状态；0 表示事务内占位
 * @param responseBody   首次成功响应快照（JSON）
 * @param createdAt      记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String fingerprint,
        String operation,
        String actor,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
