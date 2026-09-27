package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测附件元数据：对应 observation_attachment 表的一行。
 * 原始摘要永不因脱敏修改；只有物理清除会将其置空并记录清除时刻。
 *
 * @param attachmentId    附件唯一标识
 * @param observationId   所属观测记录标识
 * @param fileName        附件文件名
 * @param contentType     附件内容类型（MIME）
 * @param sizeBytes       附件内容大小（字节，非负）
 * @param originalSummary 原始内容摘要原文；物理清除后为 null
 * @param contentCleared  原始内容是否已被物理清除
 * @param clearedAtUtc    原始内容物理清除时刻（UTC）；未清除为 null
 * @param createdAt       附件登记时间（服务器时区）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentMeta(
        String attachmentId,
        String observationId,
        String fileName,
        String contentType,
        long sizeBytes,
        String originalSummary,
        boolean contentCleared,
        Instant clearedAtUtc,
        Instant createdAt) {
}
