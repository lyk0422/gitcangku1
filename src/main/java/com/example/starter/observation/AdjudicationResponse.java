package com.example.starter.observation;

import java.time.Instant;

/**
 * 人工裁决响应：裁决记录与提交最终状态的稳定结构。
 *
 * @param submissionId       被裁决的提交标识
 * @param observationId      目标观测记录标识
 * @param decision           裁决结果：ACCEPT
 * @param reason             人工裁决理由
 * @param operator           裁决操作者标识
 * @param observationVersion 裁决合并后观测记录的版本号
 * @param decidedAtUtc       裁决完成时刻（UTC）
 */
public record AdjudicationResponse(
        String submissionId,
        String observationId,
        String decision,
        String reason,
        String operator,
        int observationVersion,
        Instant decidedAtUtc) {

    /**
     * 由裁决记录与提交记录构造响应。
     */
    public static AdjudicationResponse of(AdjudicationRecord adjudication, SubmissionRecord submission) {
        return new AdjudicationResponse(adjudication.submissionId(), submission.observationId(),
                adjudication.decision(), adjudication.reason(), adjudication.operator(),
                adjudication.observationVersion(), adjudication.decidedAtUtc());
    }
}
