package com.example.starter.sampling;

import java.time.Instant;

/**
 * 采样冲突簇：对应 sampling_conflict_cluster 表的一行；已人工裁决的簇不再重算。
 *
 * @param clusterId        冲突簇标识：批次标识加序号
 * @param batchId          产生该簇的批量合并标识
 * @param deviceId         观测设备标识
 * @param gridX            网格X坐标
 * @param gridY            网格Y坐标
 * @param status           簇状态：PENDING 待裁决 / ADJUDICATED 已人工裁决
 * @param winningContent   裁决采用的观测内容；未裁决为 null
 * @param adjudicatedBy    裁决操作者标识；未裁决为 null
 * @param adjudicatedAtUtc 裁决完成时刻（UTC）；未裁决为 null
 */
public record ConflictCluster(
        String clusterId,
        String batchId,
        String deviceId,
        int gridX,
        int gridY,
        ClusterStatus status,
        String winningContent,
        String adjudicatedBy,
        Instant adjudicatedAtUtc) {
}
