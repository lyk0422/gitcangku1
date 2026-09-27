package com.example.starter.observation;

import java.time.Instant;

/**
 * 附件脱敏版本：对应 redaction_version 表的一行。
 * 版本行、脱敏内容、替换说明落库后不可变；撤销只翻转公开展示资格字段。
 *
 * @param attachmentId    被脱敏附件标识
 * @param observationId   脱敏时附件所属观测标识
 * @param version         该附件的脱敏版本号（从 1 开始按附件递增）
 * @param ruleVersion     本版本使用的脱敏规则版本
 * @param redactedSummary 脱敏后展示摘要（不可变）
 * @param replacementNote 替换说明（不可变）
 * @param status          公开展示资格：ACTIVE / REVOKED
 * @param requestId       生成该版本的请求标识
 * @param operator        执行脱敏的操作者标识
 * @param createdAtUtc    脱敏版本生成时刻（UTC）
 * @param revokedAtUtc    公开展示资格撤销时刻（UTC）；未撤销为 null
 * @param revokeReason    脱敏撤销理由；未撤销为 null
 * @param revokedBy       撤销操作者；未撤销为 null
 * @param revokeRequestId 撤销请求标识；未撤销为 null
 */
public record RedactionVersionRecord(
        String attachmentId,
        String observationId,
        int version,
        String ruleVersion,
        String redactedSummary,
        String replacementNote,
        RedactionStatus status,
        String requestId,
        String operator,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason,
        String revokedBy,
        String revokeRequestId) {
}
