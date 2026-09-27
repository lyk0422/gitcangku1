package com.example.starter.observation;

import java.time.Instant;

/**
 * 附件脱敏版本：对应 attachment_redaction 表的一行，追加写入后永不更新或删除。
 * 撤销只把 {@code publicActive} 置为 false 并记录撤销时刻/理由，版本内容全部保留。
 *
 * @param redactionId      脱敏版本记录全局唯一标识
 * @param batchId          脱敏批次标识（同一次请求内的多附件共用）
 * @param requestId        生成该版本的请求标识
 * @param attachmentId     被脱敏的附件标识
 * @param observationId    附件所属观测记录标识
 * @param attachmentVersion 该附件的脱敏版本号，从 1 开始递增
 * @param ruleCode         脱敏规则标识（快照）
 * @param ruleVersion      脱敏时引用的规则版本号（快照）
 * @param redactedSummary  受限展示用脱敏摘要
 * @param replacementNote  替换说明
 * @param reason           本次脱敏理由
 * @param operator         发起脱敏的操作者标识
 * @param publicActive     是否具备公开展示资格
 * @param createdAtUtc     脱敏版本生成时刻（UTC）
 * @param revokedAtUtc     撤销时刻（UTC）；未撤销为 null
 * @param revokeReason     撤销理由；未撤销为 null
 * @param revokedBy        执行撤销的操作者标识；未撤销为 null
 */
public record RedactionVersion(
        String redactionId,
        String batchId,
        String requestId,
        String attachmentId,
        String observationId,
        int attachmentVersion,
        String ruleCode,
        int ruleVersion,
        String redactedSummary,
        String replacementNote,
        String reason,
        String operator,
        boolean publicActive,
        Instant createdAtUtc,
        Instant revokedAtUtc,
        String revokeReason,
        String revokedBy) {

    /**
     * 基于一条已落库版本构造撤销后的视图（内存对象使用，不改变不可变落库语义）。
     */
    public RedactionVersion revoked(Instant at, String reason, String by) {
        return new RedactionVersion(redactionId, batchId, requestId, attachmentId, observationId,
                attachmentVersion, ruleCode, ruleVersion, redactedSummary, replacementNote,
                this.reason, operator, false, createdAtUtc, at, reason, by);
    }
}
