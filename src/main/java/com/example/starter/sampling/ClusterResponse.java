package com.example.starter.sampling;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 冲突簇响应：返回簇状态与裁决结果。
 *
 * @param clusterId        冲突簇标识
 * @param batchId          产生该簇的批量合并标识
 * @param deviceId         观测设备标识
 * @param gridX            网格X坐标
 * @param gridY            网格Y坐标
 * @param status           簇状态：PENDING / ADJUDICATED
 * @param winningContent   裁决采用的观测内容；未裁决为 null
 * @param adjudicatedBy    裁决操作者标识；未裁决为 null
 * @param adjudicatedAtUtc 裁决完成时刻（UTC）；未裁决为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterResponse(
        String clusterId,
        String batchId,
        String deviceId,
        int gridX,
        int gridY,
        String status,
        String winningContent,
        String adjudicatedBy,
        Instant adjudicatedAtUtc) {

    /**
     * 由冲突簇记录构造响应。
     */
    public static ClusterResponse of(ConflictCluster cluster) {
        return new ClusterResponse(cluster.clusterId(), cluster.batchId(), cluster.deviceId(),
                cluster.gridX(), cluster.gridY(), cluster.status().name(),
                cluster.winningContent(), cluster.adjudicatedBy(), cluster.adjudicatedAtUtc());
    }
}
