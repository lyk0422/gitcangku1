package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏版本响应：明细与历史共用，字段、排序与 null 语义稳定。
 *
 * @param redactionId     脱敏版本全局唯一标识
 * @param observationId   所属观测记录唯一标识
 * @param attachmentId    被脱敏附件唯一标识
 * @param version         附件内递增脱敏版本号
 * @param ruleVersion     使用的规则版本
 * @param replacementNote 替换说明（脱敏后公开展示的受限摘要原文）
 * @param status          公开展示状态：ACTIVE / REVOKED
 * @param requestId       生成该版本的请求标识
 * @param createdAtUtc    生成时刻（UTC，ISO-8601）
 * @param revokedAtUtc    撤销公开展示资格时刻（UTC）；未撤销不返回
 * @param revokeReason    撤销理由；未撤销不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionVersionResponse(
        String redactionId,
        String observationId,
        String attachmentId,
        int version,
        String ruleVersion,
        String replacementNote,
        String status,
        String requestId,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason) {

    public static RedactionVersionResponse of(RedactionVersionRecord record) {
        return new RedactionVersionResponse(record.redactionId(), record.observationId(), record.attachmentId(),
                record.version(), record.ruleVersion(), record.replacementNote(),
                record.active() ? "ACTIVE" : "REVOKED", record.requestId(), record.createdAtUtc(),
                record.revokedAtUtc(), record.revokeReason());
    }
}
