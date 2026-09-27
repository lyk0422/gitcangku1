package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等记录。requestId 同参重放返回首次结果，异参返回 409；
 * 仅在业务成功提交后写入，失败事务回滚不占键。
 *
 * @param id             幂等记录 ID（自增）
 * @param scope          幂等作用域（如 reading / review）
 * @param requestId      幂等键
 * @param fingerprint    请求参数指纹，用于区分同参重放与异参冲突
 * @param responseStatus 首次响应的 HTTP 状态码
 * @param responseBody   首次响应体（JSON），重放时原样返回
 * @param createdAt      记录时间（UTC）
 */
public record IdempotencyRecord(
        long id,
        String scope,
        String requestId,
        String fingerprint,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
