package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测附件元数据：对应 attachment 表一行，只保存内容摘要而非附件内容本身。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录唯一标识
 * @param contentSummary 附件原始内容摘要；物理清除后为 null（null 语义：原文不可恢复）
 * @param mediaType      附件媒体类型（如 image/jpeg）
 * @param purged         附件内容是否已被物理清除
 * @param createdAtUtc   附件登记完成时刻（UTC）
 * @param purgedAtUtc    物理清除完成时刻（UTC）；未清除为 null
 */
public record AttachmentRecord(
        String attachmentId,
        String observationId,
        String contentSummary,
        String mediaType,
        boolean purged,
        Instant createdAtUtc,
        Instant purgedAtUtc) {
}
