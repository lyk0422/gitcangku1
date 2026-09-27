package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 冲突簇合并快照：对应 cluster_merge_snapshot 表的一行，合并时刻固化，不可变、永不更新。
 *
 * @param clusterId           冲突簇标识
 * @param status              合并结果：MERGED 已自动定案 / PENDING_MANUAL 待人工裁决
 * @param winnerSubmissionId  胜出提交标识；待人工裁决时为 null
 * @param totalCount          簇内观测提交总数
 * @param affectedCount       簇内维护受影响提交数
 * @param eligibleCount       可参与自动胜出的未受影响提交数
 * @param marksJson           各提交维护标记快照（JSON 数组原文）
 * @param requestId           合并请求标识
 * @param mergedAtUtc         合并完成时刻（UTC）
 */
public record MergeSnapshotRecord(
        String clusterId,
        String status,
        String winnerSubmissionId,
        int totalCount,
        int affectedCount,
        int eligibleCount,
        String marksJson,
        String requestId,
        Instant mergedAtUtc) {

    /** 合并结果：已自动定案。 */
    public static final String STATUS_MERGED = "MERGED";
    /** 合并结果：簇内仅含维护受影响观测，待人工裁决。 */
    public static final String STATUS_PENDING_MANUAL = "PENDING_MANUAL";
}
