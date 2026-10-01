package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等记录（表 idempotency_record）。requestId 同参重放首次成功结果，异参返回 409；
 * 仅成功请求占键，失败请求回滚后不占键。
 *
 * @param requestId    幂等请求 ID，全局唯一
 * @param endpoint     接口标识
 * @param requestHash  规范化请求参数 SHA-256（十六进制）
 * @param responseBody 首次成功响应体（JSON），用于同参重放
 * @param createdAt    记录时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String endpoint,
        String requestHash,
        String responseBody,
        Instant createdAt) {
}