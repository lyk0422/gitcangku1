package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 固化的附件证据引用视图：返回快照当时的摘要原文，后续脱敏/撤销/清除不改变其内容。
 *
 * @param sourceType            证据来源：MERGE / RESOLVE
 * @param sourceRequestId       生成该证据的请求标识
 * @param resolutionId          人工冲突解决记录标识；MERGE 来源为 null
 * @param observationId         观测记录标识
 * @param observationVersion    固化时指向的观测版本号
 * @param attachmentId          被引用的附件标识
 * @param kind                  引用类型：ORIGINAL / REDACTED
 * @param redactionId           引用的脱敏版本标识；ORIGINAL 时为 null
 * @param redactionVersion      引用时的脱敏版本号；ORIGINAL 时为 null
 * @param summaryText           固化时刻的摘要原文快照
 * @param contentClearedAtFix   固化时刻原始内容是否已被物理清除
 * @param fixedAtUtc            证据固化时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotRefResponse(
        String sourceType,
        String sourceRequestId,
        String resolutionId,
        String observationId,
        int observationVersion,
        String attachmentId,
        String kind,
        String redactionId,
        Integer redactionVersion,
        String summaryText,
        boolean contentClearedAtFix,
        Instant fixedAtUtc) {

    /**
     * 由持久化记录构造响应视图。
     */
    public static SnapshotRefResponse of(SnapshotAttachmentRef ref) {
        return new SnapshotRefResponse(
                ref.sourceType(), ref.sourceRequestId(), ref.resolutionId(), ref.observationId(),
                ref.observationVersion(), ref.attachmentId(), ref.original() ? "ORIGINAL" : "REDACTED",
                ref.redactionId(), ref.redactionVersion(), ref.summaryText(),
                ref.contentClearedAtFix(), ref.fixedAtUtc());
    }
}
