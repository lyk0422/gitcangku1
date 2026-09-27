package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 冲突簇人工裁决记录（不可变）：可选择受维护影响的观测，但必须写入裁决理由与裁决人。
 *
 * @param adjudicationId     全局唯一裁决记录标识
 * @param snapshotId         被裁决的待处理快照主键
 * @param clusterId          冲突簇标识
 * @param stationId          所属观测站标识
 * @param observationId      裁决选中的观测标识（可为受维护影响观测）
 * @param maintenanceAffected 选中观测是否受维护影响（固化值）
 * @param reason             裁决理由（不允许为空）
 * @param adjudicator        裁决人标识（不允许为空）
 * @param requestId          生成该裁决的请求标识
 * @param adjudicatedAtUtc   裁决完成时刻（UTC）
 */
public record ClusterAdjudication(
        String adjudicationId,
        long snapshotId,
        String clusterId,
        String stationId,
        String observationId,
        boolean maintenanceAffected,
        String reason,
        String adjudicator,
        String requestId,
        Instant adjudicatedAtUtc) {
}
