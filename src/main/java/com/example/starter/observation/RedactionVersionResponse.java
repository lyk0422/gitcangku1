package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏版本明细（证据权限）：完整呈现一个脱敏版本的不可变内容与撤销信息。
 *
 * @param attachmentId    被脱敏附件标识
 * @param observationId   脱敏时附件所属观测标识
 * @param version         脱敏版本号
 * @param ruleVersion     使用的规则版本
 * @param redactedSummary 脱敏后展示摘要
 * @param replacementNote 替换说明
 * @param status          公开展示资格：ACTIVE / REVOKED
 * @param requestId       生成该版本的请求标识
 * @param operator        执行脱敏的操作者标识
 * @param createdAtUtc    脱敏版本生成时刻（UTC，ISO-8601）
 * @param revokedAtUtc    撤销时刻（UTC，ISO-8601）；未撤销为 null
 * @param revokeReason    撤销理由；未撤销为 null
 * @param revokedBy       撤销操作者；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionVersionResponse(
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
        String revokedBy) {

    public static RedactionVersionResponse of(RedactionVersionRecord record) {
        return new RedactionVersionResponse(record.attachmentId(), record.observationId(),
                record.version(), record.ruleVersion(), record.redactedSummary(),
                record.replacementNote(), record.status(), record.requestId(), record.operator(),
                record.createdAtUtc(), record.revokedAtUtc(), record.revokeReason(),
                record.revokedBy());
    }
}
