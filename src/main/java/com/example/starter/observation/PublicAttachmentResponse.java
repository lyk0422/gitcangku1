package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 公开附件视图：只返回最新有效（ACTIVE）脱敏版本的受限展示内容，不暴露原始摘要。
 *
 * @param attachmentId    附件唯一标识
 * @param observationId   所属观测记录标识
 * @param version         当前生效的脱敏版本号
 * @param ruleVersion     该版本使用的规则版本
 * @param redactedSummary 脱敏后展示摘要
 * @param createdAtUtc    该脱敏版本生成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicAttachmentResponse(
        String attachmentId,
        String observationId,
        int version,
        String ruleVersion,
        String redactedSummary,
        Instant createdAtUtc) {

    public static PublicAttachmentResponse of(RedactionVersionRecord record) {
        return new PublicAttachmentResponse(record.attachmentId(), record.observationId(),
                record.version(), record.ruleVersion(), record.redactedSummary(),
                record.createdAtUtc());
    }
}
