package com.example.starter.observation;

import java.time.Instant;

/**
 * 附件脱敏版本：对应 attachment_redaction 表一行，只新增不修改（撤销仅置状态与撤销时刻）。
 *
 * @param redactionId     脱敏版本全局唯一标识
 * @param observationId   所属观测记录唯一标识
 * @param attachmentId    被脱敏附件唯一标识
 * @param version         同一附件内递增脱敏版本号，从 1 开始
 * @param ruleVersion     本次脱敏使用的规则版本
 * @param replacementNote 替换说明（脱敏后公开展示的受限摘要原文）
 * @param active          公开展示资格：true=ACTIVE 有效；false=REVOKED 已撤销仅保留历史
 * @param requestId       生成该版本的请求标识
 * @param createdAtUtc    脱敏版本生成时刻（UTC）
 * @param revokedAtUtc    撤销公开展示资格时刻（UTC）；未撤销为 null
 * @param revokeReason    撤销理由；未撤销为 null
 */
public record RedactionVersionRecord(
        String redactionId,
        String observationId,
        String attachmentId,
        int version,
        String ruleVersion,
        String replacementNote,
        boolean active,
        String requestId,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason) {
}
