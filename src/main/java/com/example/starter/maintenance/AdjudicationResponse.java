package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 人工裁决响应：包含所选提交的维护标记，证据查询同时展示。
 *
 * @param adjudicationId        全局唯一裁决记录标识
 * @param clusterId             冲突簇标识
 * @param chosenSubmissionId    人工选择的胜出提交标识
 * @param chosenMaintenanceFlag 所选提交的维护标记（可为 MAINTENANCE_AFFECTED）
 * @param reason                裁决理由
 * @param adjudicator           裁决人标识
 * @param adjudicatedAtUtc      裁决完成时刻（UTC）
 * @param clusterStatus         裁决后冲突簇状态（恒为 MERGED）
 */
public record AdjudicationResponse(
        String adjudicationId,
        String clusterId,
        String chosenSubmissionId,
        String chosenMaintenanceFlag,
        String reason,
        String adjudicator,
        Instant adjudicatedAtUtc,
        String clusterStatus) {
}
