package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测附件元数据：对应 observation_attachment 表的一行。
 * 归属一经登记不可变更；原始内容摘要业务不可修改，仅物理清除可置空（不可恢复）。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录标识（归属不可变更）
 * @param filename       附件文件名；可为 null
 * @param mediaType      附件媒体类型；可为 null
 * @param contentSummary 原始内容摘要；物理清除后为 null
 * @param contentDigest  原始内容摘要的 SHA-256 指纹，物理清除后仍保留
 * @param contentPurged  附件内容是否已物理清除
 * @param purgedAtUtc    物理清除完成时刻（UTC）；未清除为 null
 * @param createdAtUtc   附件登记完成时刻（UTC）
 */
public record AttachmentRecord(
        String attachmentId,
        String observationId,
        String filename,
        String mediaType,
        String contentSummary,
        String contentDigest,
        boolean contentPurged,
        Instant purgedAtUtc,
        Instant createdAtUtc) {
}
