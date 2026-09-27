package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇合并记录：对应 cluster_merge 表一行。
 *
 * @param mergeId                     合并记录标识（取合并请求 requestId）
 * @param observationId               观测记录唯一标识
 * @param state                       合并状态
 * @param baseVersion                 合并前观测当前版本；记录尚不存在时为 null
 * @param newVersion                  合并/裁决后指向版本；PENDING_MANUAL 未落版本时为 null
 * @param contentChanged              是否产生版本内容变化
 * @param submissionIds               簇内提交标识有序列表（按提交时刻排序）
 * @param clusterSize                 簇内提交数量（实际值）
 * @param affectedCount               簇内受影响提交数量（实际值）
 * @param winningSubmissionId         胜出提交标识；待裁定时为 null
 * @param decidedMaintenanceAffected  胜出内容固化的受影响标记；待裁定时为 null
 * @param decidedWindowId             胜出内容固化的窗口标识；自动胜出/无命中为 null
 * @param decidedWindowVersion        胜出内容固化的维护版本号；自动胜出/无命中为 null
 * @param adjudicator                 裁决人；自动合并为 null，人工裁决必填
 * @param reason                      裁决理由；自动合并为 null，人工裁决必填
 * @param createdAtUtc                簇合并提交完成时刻（UTC）
 * @param decidedAtUtc                人工裁决完成时刻（UTC）；自动合并为 null
 */
public record ClusterMergeRecord(
        String mergeId,
        String observationId,
        ClusterMergeState state,
        Integer baseVersion,
        Integer newVersion,
        boolean contentChanged,
        List<String> submissionIds,
        int clusterSize,
        int affectedCount,
        String winningSubmissionId,
        Boolean decidedMaintenanceAffected,
        String decidedWindowId,
        Integer decidedWindowVersion,
        String adjudicator,
        String reason,
        Instant createdAtUtc,
        Instant decidedAtUtc) {
}
