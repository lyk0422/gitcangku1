package com.example.starter.observation;

import java.time.Instant;

/**
 * 附件脱敏版本：对应 attachment_redaction 表的一行。
 * 每次脱敏生成递增版本，仅追加受限展示版本；撤销仅撤销公开展示资格，记录本身不可变。
 *
 * @param attachmentId     附件唯一标识
 * @param redactionVersion 脱敏版本号，按附件从 1 开始递增
 * @param ruleVersion      本次脱敏使用的规则版本
 * @param redactedSummary  受限展示摘要
 * @param replacementNote  替换说明
 * @param status           状态：EFFECTIVE / REVOKED
 * @param createdAtUtc     创建时刻（UTC）
 * @param revokedAtUtc     撤销时刻（UTC），未撤销为 null
 * @param revokeReason     撤销理由，未撤销为 null
 * @param requestId        创建该版本的请求标识
 */
public record RedactionVersion(
        String attachmentId,
        int redactionVersion,
        String ruleVersion,
        String redactedSummary,
        String replacementNote,
        String status,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason,
        String requestId) {

    public static final String STATUS_EFFECTIVE = "EFFECTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
}
