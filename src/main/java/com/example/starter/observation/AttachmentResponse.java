package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 附件元数据响应。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录唯一标识
 * @param mediaType      附件媒体类型
 * @param purged         附件内容是否已被物理清除
 * @param createdAtUtc   登记完成时刻（UTC，ISO-8601）
 * @param purgedAtUtc    物理清除完成时刻（UTC）；未清除不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentResponse(
        String attachmentId,
        String observationId,
        String mediaType,
        boolean purged,
        Instant createdAtUtc,
        Instant purgedAtUtc) {

    public static AttachmentResponse of(AttachmentRecord record) {
        return new AttachmentResponse(record.attachmentId(), record.observationId(), record.mediaType(),
                record.purged(), record.createdAtUtc(), record.purgedAtUtc());
    }
}
