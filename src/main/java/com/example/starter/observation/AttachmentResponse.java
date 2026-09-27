package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 附件登记响应。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录唯一标识
 * @param contentSummary 附件原始内容摘要
 * @param createdAtUtc   附件登记时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentResponse(
        String attachmentId,
        String observationId,
        String contentSummary,
        Instant createdAtUtc) {

    /**
     * 由附件记录构造响应。
     */
    public static AttachmentResponse of(AttachmentRecord record) {
        return new AttachmentResponse(record.attachmentId(), record.observationId(),
                record.contentSummary(), record.createdAtUtc());
    }
}
