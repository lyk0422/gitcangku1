package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 幂等去重记录。身份为操作+操作者+资源+结构化参数 JSON（含期望版本），
 * 分字段存储避免分隔符碰撞；仅成功提交才占键。
 *
 * @param requestId      幂等请求键，全局唯一
 * @param operation      操作类型
 * @param operator       操作者
 * @param resource       操作资源标识
 * @param paramsJson     全部请求参数的 JSON
 * @param responseStatus 首次成功响应的 HTTP 状态码
 * @param responseBody   首次成功响应的完整响应体（JSON）
 * @param createdAt      记录创建时间（UTC）
 */
public record IdempotencyRecord(
        String requestId,
        String operation,
        String operator,
        String resource,
        String paramsJson,
        int responseStatus,
        String responseBody,
        Instant createdAt) {
}
