package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 人工裁决记录：对应 cluster_adjudication 表的一行，不可变追加。
 *
 * @param adjudicationId     全局唯一裁决记录标识
 * @param clusterId          冲突簇标识
 * @param chosenSubmissionId 人工选择的胜出提交标识，可为维护受影响观测
 * @param reason             裁决理由（必填）
 * @param adjudicator        裁决人标识（必填）
 * @param requestId          裁决请求标识
 * @param adjudicatedAtUtc   裁决完成时刻（UTC）
 */
public record AdjudicationRecord(
        String adjudicationId,
        String clusterId,
        String chosenSubmissionId,
        String reason,
        String adjudicator,
        String requestId,
        Instant adjudicatedAtUtc) {
}
