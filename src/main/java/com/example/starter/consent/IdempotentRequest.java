package com.example.starter.consent;

import java.time.LocalDateTime;

/**
 * 幂等请求实体，对应 idempotency_request 表；仅成功请求落库，失败随事务回滚不占用 requestId。
 *
 * @param requestId    幂等请求标识
 * @param requestType  请求类型：GRANT / REVOKE / WRITE
 * @param fingerprint  请求参数指纹，用于识别同 requestId 参数变化
 * @param httpStatus   原成功响应的 HTTP 状态码
 * @param responseBody 原成功响应体（JSON）
 * @param createdAt    首次成功时间（本地时间）
 */
public record IdempotentRequest(
        String requestId,
        String requestType,
        String fingerprint,
        int httpStatus,
        String responseBody,
        LocalDateTime createdAt) {
}
