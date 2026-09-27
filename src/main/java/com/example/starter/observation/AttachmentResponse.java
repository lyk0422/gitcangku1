package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 附件证据明细（证据权限）：包含原始内容摘要（未清除时）与清除状态。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录标识
 * @param filename       附件文件名；可为 null
 * @param mediaType      附件媒体类型；可为 null
 * @param contentSummary 原始内容摘要；物理清除后为 null
 * @param contentDigest  原始内容摘要的 SHA-256 指纹
 * @param contentPurged  附件内容是否已物理清除
 * @param purgedAtUtc    物理清除完成时刻（UTC，ISO-8601）；未清除为 null
 * @param createdAtUtc   附件登记完成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentResponse(
        String attachmentId,
        String observationId,
        String filename,
        String mediaType,
        String contentSummary,
        String contentDigest,
        boolean contentPurged,
        Instant purgedAtUtc,
        Instant createdAtUtc) {

    public static AttachmentResponse of(AttachmentRecord record) {
        return new AttachmentResponse(record.attachmentId(), record.observationId(),
                record.filename(), record.mediaType(), record.contentSummary(),
                record.contentDigest(), record.contentPurged(), record.purgedAtUtc(),
                record.createdAtUtc());
    }
}
