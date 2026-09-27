package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 撤销结果响应：区分脱敏版本撤销与规则版本撤销，返回实际状态与撤销时刻。
 *
 * @param kind         撤销对象：REDACTION / RULE
 * @param targetId     撤销对象标识（redactionId 或 ruleVersion）
 * @param status       撤销后状态：固定 REVOKED
 * @param requestId    请求标识
 * @param reason       撤销理由
 * @param revokedAtUtc 撤销完成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RevokeResultResponse(
        String kind,
        String targetId,
        String status,
        String requestId,
        String reason,
        Instant revokedAtUtc) {
}
