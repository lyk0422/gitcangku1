package com.example.starter.domain;

import java.time.Instant;

/**
 * 幂等请求日志：requestId 唯一，同参重放返回原响应，异参拒绝；失败事务不写入。
 *
 * @param requestId 请求标识，全项目唯一
 * @param operation 操作类型：COLLECT_SAMPLE 采集 / HANDOVER 交接
 * @param payloadHash 请求体 SHA-256 摘要（十六进制）
 * @param httpStatus 首次成功响应的 HTTP 状态码
 * @param responseBody 首次成功响应体（JSON），用于重放
 * @param createdAt 记录时刻（UTC）
 */
public record RequestLog(String requestId, String operation, String payloadHash, int httpStatus,
        String responseBody, Instant createdAt) {
}