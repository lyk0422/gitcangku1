package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇响应：成员标识、当前胜出观测与人工裁决状态。
 *
 * @param clusterId            冲突簇唯一标识
 * @param memberObservationIds 簇成员观测标识（按标识排序）
 * @param winnerObservationId  当前胜出观测标识
 * @param manuallyResolved     是否已人工裁决
 * @param resolvedBy           人工裁决操作者标识；未裁决时为 null
 * @param resolvedAt           人工裁决时刻（UTC）；未裁决时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterResponse(
        String clusterId,
        List<String> memberObservationIds,
        String winnerObservationId,
        boolean manuallyResolved,
        String resolvedBy,
        Instant resolvedAt) {

    public static ClusterResponse of(GeoCluster cluster, List<String> memberObservationIds) {
        return new ClusterResponse(cluster.clusterId(), List.copyOf(memberObservationIds),
                cluster.winnerObservationId(), cluster.manuallyResolved(),
                cluster.resolvedBy(), cluster.resolvedAtUtc());
    }
}
