package com.example.starter.observation;

import java.time.Instant;

/**
 * 附件登记响应。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录标识
 * @param contentSummary 原始内容摘要
 * @param createdAtUtc   登记时刻（UTC，ISO-8601）
 */
public record AttachmentResponse(
        String attachmentId,
        String observationId,
        String contentSummary,
        Instant createdAtUtc) {

    public static AttachmentResponse of(Attachment attachment) {
        return new AttachmentResponse(attachment.attachmentId(), attachment.observationId(),
                attachment.contentSummary(), attachment.createdAtUtc());
    }
}
