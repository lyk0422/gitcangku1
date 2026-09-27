package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 附件展示视图：公开查询只返回最新有效（ACTIVE）脱敏版本的替换说明；
 * 无有效脱敏版本时返回原始摘要（物理清除后 summary 为 null）。
 *
 * @param attachmentId     附件唯一标识
 * @param observationId    所属观测记录唯一标识
 * @param mediaType        附件媒体类型
 * @param purged           附件内容是否已被物理清除
 * @param displayType      展示类型：ORIGINAL 原始摘要 / REDACTION 脱敏版本
 * @param summary          展示摘要原文；物理清除后为 null
 * @param redactionId      展示来源脱敏版本标识；ORIGINAL 时不返回
 * @param redactionVersion 展示来源脱敏版本号；ORIGINAL 时不返回
 * @param ruleVersion      展示来源规则版本；ORIGINAL 时不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentViewResponse(
        String attachmentId,
        String observationId,
        String mediaType,
        boolean purged,
        String displayType,
        String summary,
        String redactionId,
        Integer redactionVersion,
        String ruleVersion) {

    /**
     * 原始摘要视图（公开无有效脱敏，或证据角色按版本 0 读取）。
     */
    public static AttachmentViewResponse original(AttachmentRecord attachment) {
        return new AttachmentViewResponse(attachment.attachmentId(), attachment.observationId(),
                attachment.mediaType(), attachment.purged(), "ORIGINAL",
                attachment.contentSummary(), null, null, null);
    }

    /**
     * 脱敏版本视图。
     */
    public static AttachmentViewResponse redacted(AttachmentRecord attachment, RedactionVersionRecord version) {
        return new AttachmentViewResponse(attachment.attachmentId(), attachment.observationId(),
                attachment.mediaType(), attachment.purged(), "REDACTION",
                version.replacementNote(), version.redactionId(), version.version(), version.ruleVersion());
    }
}
