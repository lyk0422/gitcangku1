package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 脱敏版本响应：公开查询只返回最新有效版本的受限字段；证据查询返回全部版本并含撤销信息。
 *
 * @param redactionId       脱敏版本记录全局唯一标识
 * @param batchId           脱敏批次标识
 * @param requestId         生成该版本的请求标识
 * @param attachmentId      被脱敏的附件标识
 * @param observationId     附件所属观测记录标识
 * @param attachmentVersion 该附件的脱敏版本号（从 1 递增）
 * @param ruleCode          脱敏规则标识
 * @param ruleVersion       脱敏规则版本号
 * @param redactedSummary   受限展示用脱敏摘要
 * @param replacementNote   替换说明
 * @param reason            脱敏理由
 * @param operator          发起脱敏的操作者标识
 * @param publicActive      是否具备公开展示资格
 * @param createdAtUtc      脱敏版本生成时刻（UTC）
 * @param revokedAtUtc      撤销时刻（UTC）；未撤销为 null
 * @param revokeReason      撤销理由；未撤销为 null
 * @param revokedBy         执行撤销的操作者标识；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RedactionVersionResponse(
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
     * 公开视图：已撤销版本不含撤销理由等运维字段（公开查询本来也不会返回已撤销版本）。
     */
    public static RedactionVersionResponse publicView(RedactionVersion version) {
        return new RedactionVersionResponse(
                version.redactionId(), version.batchId(), version.requestId(),
                version.attachmentId(), version.observationId(), version.attachmentVersion(),
                version.ruleCode(), version.ruleVersion(), version.redactedSummary(),
                version.replacementNote(), version.reason(), version.operator(),
                version.publicActive(), version.createdAtUtc(), null, null, null);
    }

    /**
     * 证据视图：保留撤销时刻、撤销理由与操作者等完整历史字段。
     */
    public static RedactionVersionResponse evidenceView(RedactionVersion version) {
        return new RedactionVersionResponse(
                version.redactionId(), version.batchId(), version.requestId(),
                version.attachmentId(), version.observationId(), version.attachmentVersion(),
                version.ruleCode(), version.ruleVersion(), version.redactedSummary(),
                version.replacementNote(), version.reason(), version.operator(),
                version.publicActive(), version.createdAtUtc(),
                version.revokedAtUtc(), version.revokeReason(), version.revokedBy());
    }
}
