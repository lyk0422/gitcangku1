package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等请求记录。requestId 全局唯一：同参重放返回首次结果，异参返回 409；
 * 仅成功提交的结果占键，失败回滚后不留记录。
 *
 * @param requestId      幂等请求 ID
 * @param operation      操作类型
 * @param paramsHash     规范化参数的 SHA-256（十六进制）
 * @param responseStatus 首次结果的 HTTP 状态码
 * @param responseBody   首次结果的响应体 JSON
 * @param createdAt      记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String operation,
        String paramsHash,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}