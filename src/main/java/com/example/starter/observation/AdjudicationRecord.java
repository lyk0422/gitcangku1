package com.example.starter.observation;

import java.time.Instant;

/**
 * 人工裁决记录：对应 submission_adjudication 表的一行，不可变、永不更新或删除。
 *
 * @param submissionId       被裁决的提交标识
 * @param requestId          裁决请求的幂等标识
 * @param operator           裁决操作者标识
 * @param decision           裁决结果：ACCEPT
 * @param reason             人工裁决理由
 * @param observationVersion 裁决合并后观测记录的版本号
 * @param decidedAtUtc       裁决完成时刻（UTC）
 */
public record AdjudicationRecord(
        String submissionId,
        String requestId,
        String operator,
        String decision,
        String reason,
        int observationVersion,
        Instant decidedAtUtc) {
}
