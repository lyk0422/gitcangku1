package com.example.starter.db;

import java.time.Instant;

/**
 * idempotency_record 表行：写操作幂等去重记录。
 *
 * @param requestId      全局唯一请求编号
 * @param actorId        操作者编号（幂等键组成部分）
 * @param role           操作者角色（幂等键组成部分）
 * @param operation      操作类型
 * @param paramsHash     归一化请求参数指纹
 * @param responseStatus 原成功响应 HTTP 状态码
 * @param responseBody   原成功响应 JSON
 * @param createdAt      记录时间（UTC）
 */
public record IdempotencyRow(
        String requestId,
        String actorId,
        String role,
        String operation,
        String paramsHash,
        int responseStatus,
        String responseBody,
        Instant createdAt
) {
}
