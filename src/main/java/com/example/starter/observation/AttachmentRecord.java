package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测附件元数据：对应 attachment 表一行。附件携带原始内容摘要并归属于一条观测记录；
 * 原始摘要永不更新，只能被物理清除（contentPurged=true 时 contentSummary 为 null 且不可恢复）。
 *
 * @param attachmentId   附件唯一标识
 * @param observationId  所属观测记录唯一标识
 * @param contentSummary 附件原始内容摘要；物理清除后为 null
 * @param contentPurged  原始内容是否已被物理清除
 * @param createdAtUtc   附件登记时刻（UTC）
 * @param purgedAtUtc    原始内容物理清除时刻（UTC）；未清除为 null
 */
public record AttachmentRecord(
        String attachmentId,
        String observationId,
        String contentSummary,
        boolean contentPurged,
        Instant createdAtUtc,
        Instant purgedAtUtc) {
}
