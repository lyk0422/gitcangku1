package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测附件元数据：对应 observation_attachment 表的一行。
 * 附件属于且仅属于一条观测记录；原始内容摘要登记后不可修改。
 *
 * @param attachmentId  附件唯一标识
 * @param observationId 所属观测记录标识
 * @param contentSummary 原始内容摘要（不可修改）
 * @param createdAtUtc  登记时刻（UTC）
 */
public record Attachment(
        String attachmentId,
        String observationId,
        String contentSummary,
        Instant createdAtUtc) {
}
