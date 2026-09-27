package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 物理清除附件原始内容响应。
 *
 * @param attachmentId 附件唯一标识
 * @param contentPurged 清除后恒为 true
 * @param purgedAtUtc   物理清除时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurgeAttachmentResponse(
        String attachmentId,
        boolean contentPurged,
        Instant purgedAtUtc) {
}
