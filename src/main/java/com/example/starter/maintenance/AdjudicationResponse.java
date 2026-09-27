package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 人工裁决记录响应：裁决可选择受维护影响观测，响应同时显示该观测的维护标记。
 *
 * @param adjudicationId      全局唯一裁决记录标识
 * @param snapshotId          被裁决快照主键
 * @param clusterId           冲突簇标识
 * @param stationId           所属观测站标识
 * @param observationId       裁决选中的观测标识
 * @param maintenanceAffected 选中观测是否受维护影响
 * @param reason              裁决理由
 * @param adjudicator         裁决人
 * @param requestId           生成裁决的请求标识
 * @param adjudicatedAtUtc    裁决完成时刻（UTC）
 */
public record AdjudicationResponse(
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

    /**
     * 由领域对象构造响应。
     */
    public static AdjudicationResponse of(ClusterAdjudication adjudication) {
        return new AdjudicationResponse(
                adjudication.adjudicationId(),
                adjudication.snapshotId(),
                adjudication.clusterId(),
                adjudication.stationId(),
                adjudication.observationId(),
                adjudication.maintenanceAffected(),
                adjudication.reason(),
                adjudication.adjudicator(),
                adjudication.requestId(),
                adjudication.adjudicatedAtUtc());
    }
}
