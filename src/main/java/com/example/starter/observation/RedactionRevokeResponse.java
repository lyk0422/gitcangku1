package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 撤销附件脱敏响应：撤销仅取消公开展示资格，脱敏版本、理由与撤销时刻保留。
 *
 * @param attachmentId     附件唯一标识
 * @param redactionVersion 被撤销的脱敏版本号
 * @param active           撤销后恒为 false
 * @param revokeReason     撤销原因
 * @param revokedAtUtc     撤销时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionRevokeResponse(
        String attachmentId,
        int redactionVersion,
        boolean active,
        String revokeReason,
        Instant revokedAtUtc) {
}
