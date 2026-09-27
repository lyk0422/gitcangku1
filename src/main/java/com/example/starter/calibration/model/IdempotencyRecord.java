package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等记录。requestId 同参重放首次结果，异参 409；仅成功（2xx）响应占键。
 *
 * @param id             幂等记录 ID（自增）
 * @param endpoint       端点标识
 * @param requestId      调用方请求 ID
 * @param paramsHash     规范化参数的 SHA-256 摘要（十六进制）
 * @param responseStatus 首次响应的 HTTP 状态码
 * @param responseBody   首次响应的 JSON 报文
 * @param createdAt      记录时间（UTC）
 */
public record IdempotencyRecord(
        long id,
        String endpoint,
        String requestId,
        String paramsHash,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
