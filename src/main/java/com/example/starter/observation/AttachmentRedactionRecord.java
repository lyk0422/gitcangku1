package com.example.starter.observation;

import java.time.Instant;

/**
 * 附件脱敏版本：对应 attachment_redaction 表一行，是附件的一个受限展示版本，只增不改。
 * 撤销仅将 active 置为 false 并记录原因与时刻，行本身永久保留。
 *
 * @param redactionId      全局唯一脱敏请求标识，一次请求的多个附件项共享同一标识
 * @param observationId    所属观测记录唯一标识
 * @param attachmentId     被脱敏附件标识
 * @param redactionVersion 该附件内递增的脱敏版本号，从 1 开始
 * @param requestId        生成该脱敏版本的请求标识（requestId）
 * @param ruleVersion      使用的脱敏规则版本
 * @param displaySummary   受限展示摘要：公开查询返回的替换文本
 * @param replacementNote  提交者给出的替换说明，随版本永久保留
 * @param active           是否具备公开展示资格：true=ACTIVE，false=REVOKED
 * @param revokeReason     脱敏撤销原因；未撤销为 null
 * @param createdAtUtc     脱敏版本生成时刻（UTC）
 * @param revokedAtUtc     脱敏撤销时刻（UTC）；未撤销为 null
 */
public record AttachmentRedactionRecord(
        String redactionId,
        String observationId,
        String attachmentId,
        int redactionVersion,
        String requestId,
        String ruleVersion,
        String displaySummary,
        String replacementNote,
        boolean active,
        String revokeReason,
        Instant createdAtUtc,
        Instant revokedAtUtc) {
}
