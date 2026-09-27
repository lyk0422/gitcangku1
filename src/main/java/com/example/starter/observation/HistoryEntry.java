package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏历史条目：证据角色返回全部字段；公开查询只返回有效版本且不含替换说明与撤销信息。
 *
 * @param attachmentId     附件唯一标识
 * @param version          脱敏版本号
 * @param ruleVersion      本次脱敏使用的规则版本
 * @param redactedSummary  受限展示摘要
 * @param status           状态：EFFECTIVE / REVOKED
 * @param createdAtUtc     创建时刻（UTC，ISO-8601）
 * @param revokedAtUtc     撤销时刻（UTC），未撤销为 null
 * @param revokeReason     撤销理由；公开查询为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HistoryEntry(
        String attachmentId,
        int version,
        String ruleVersion,
        String redactedSummary,
        String replacementNote,
        String status,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason) {
}
