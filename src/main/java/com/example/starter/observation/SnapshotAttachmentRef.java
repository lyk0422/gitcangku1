package com.example.starter.observation;

import java.time.Instant;

/**
 * 合并快照/人工冲突选择固化的附件证据引用：对应 snapshot_attachment_ref 表的一行，写入后不可变。
 *
 * @param id                     自增主键
 * @param sourceType             证据来源：MERGE / RESOLVE
 * @param sourceRequestId        生成该证据的请求标识
 * @param resolutionId           人工冲突解决记录标识；MERGE 来源为 null
 * @param observationId          观测记录标识
 * @param observationVersion     证据固化时指向的观测版本号
 * @param attachmentId           被引用的附件标识
 * @param original               true 表示引用原始摘要（ORIGINAL），false 表示引用指定脱敏版本（REDACTED）
 * @param redactionId            引用的脱敏版本标识；引用原始摘要时为 null
 * @param redactionVersion       引用时的脱敏版本号快照；引用原始摘要时为 null
 * @param summaryText            固化时刻的摘要原文快照
 * @param contentClearedAtFix    固化时刻附件原始内容是否已被物理清除
 * @param fixedAtUtc             证据固化时刻（UTC）
 */
public record SnapshotAttachmentRef(
        Long id,
        String sourceType,
        String sourceRequestId,
        String resolutionId,
        String observationId,
        int observationVersion,
        String attachmentId,
        boolean original,
        String redactionId,
        Integer redactionVersion,
        String summaryText,
        boolean contentClearedAtFix,
        Instant fixedAtUtc) {
}
