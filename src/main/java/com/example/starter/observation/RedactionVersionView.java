package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏版本证据视图：具备证据权限的查询按版本读取，包含原始摘要与受限摘要。
 * version 为 0 时表示原始摘要（尚未脱敏），其余脱敏字段为 null。
 *
 * @param attachmentId     附件唯一标识
 * @param version          脱敏版本号；0 表示原始摘要
 * @param ruleVersion      本次脱敏使用的规则版本；原始摘要时为 null
 * @param redactedSummary  受限展示摘要；原始摘要时为原始摘要原文
 * @param replacementNote  替换说明；原始摘要时为 null
 * @param status           状态：EFFECTIVE / REVOKED / ORIGINAL
 * @param createdAtUtc     创建时刻（UTC）；原始摘要时为附件登记时刻
 * @param revokedAtUtc     撤销时刻（UTC），未撤销为 null
 * @param revokeReason     撤销理由，未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionVersionView(
        String attachmentId,
        int version,
        String ruleVersion,
        String redactedSummary,
        String replacementNote,
        String status,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason) {

    public static final String STATUS_ORIGINAL = "ORIGINAL";
}
