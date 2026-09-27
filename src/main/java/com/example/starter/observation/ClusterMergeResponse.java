package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇合并响应：返回可区分的状态、实际数量（簇大小/受影响数）、胜出与固化维护信息；
 * 待人工裁决时胜出相关字段为空。
 *
 * @param mergeId                    合并记录标识（合并请求 requestId）
 * @param observationId              观测记录唯一标识
 * @param state                      AUTO_MERGED / PENDING_MANUAL / RESOLVED
 * @param baseVersion                合并前当前版本；记录原不存在省略
 * @param version                    合并/裁决后指向版本；待裁定时省略
 * @param contentChanged             是否产生版本内容变化
 * @param clusterSize                簇内提交数量（实际值）
 * @param affectedCount              簇内受影响提交数量（实际值）
 * @param submissionIds              簇内提交标识有序列表（按提交时刻排序）
 * @param winningSubmissionId        胜出提交标识；待裁定时省略
 * @param maintenanceAffected        胜出内容固化的受影响标记（自动胜出恒为 false，人工选择受影响观测为 true）；待裁定时省略
 * @param maintenanceWindowId        胜出内容固化的窗口标识；待裁定/无命中省略
 * @param maintenanceWindowVersion   胜出内容固化的维护版本号；待裁定/无命中省略
 * @param adjudicator                裁决人；自动合并省略
 * @param reason                     裁决理由；自动合并省略
 * @param createdAtUtc               簇合并完成时刻（UTC）
 * @param decidedAtUtc               人工裁决完成时刻（UTC）；自动合并省略
 */
public record ClusterMergeResponse(
        String mergeId,
        String observationId,
        String state,
        Integer baseVersion,
        Integer version,
        boolean contentChanged,
        int clusterSize,
        int affectedCount,
        List<String> submissionIds,
        String winningSubmissionId,
        Boolean maintenanceAffected,
        String maintenanceWindowId,
        Integer maintenanceWindowVersion,
        String adjudicator,
        String reason,
        Instant createdAtUtc,
        Instant decidedAtUtc) {

    /**
     * 由持久化记录构造响应。
     */
    public static ClusterMergeResponse of(ClusterMergeRecord record) {
        return new ClusterMergeResponse(
                record.mergeId(), record.observationId(), record.state().name(),
                record.baseVersion(), record.newVersion(), record.contentChanged(),
                record.clusterSize(), record.affectedCount(), List.copyOf(record.submissionIds()),
                record.winningSubmissionId(), record.decidedMaintenanceAffected(),
                record.decidedWindowId(), record.decidedWindowVersion(),
                record.adjudicator(), record.reason(), record.createdAtUtc(), record.decidedAtUtc());
    }
}
