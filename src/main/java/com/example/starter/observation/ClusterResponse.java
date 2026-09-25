package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 冲突簇响应：簇成员、当前胜出记录与人工裁决状态。
 *
 * @param clusterId              冲突簇唯一标识
 * @param memberObservationIds   簇成员观测记录标识列表（按提交顺序）
 * @param winnerObservationId    当前胜出观测记录标识
 * @param manuallyResolved       是否已人工裁决
 * @param resolvedObservationId  人工裁决选定的观测记录标识；未裁决时为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClusterResponse(
        String clusterId,
        List<String> memberObservationIds,
        String winnerObservationId,
        boolean manuallyResolved,
        String resolvedObservationId) {

    /**
     * 由簇记录与成员列表构造响应。
     */
    public static ClusterResponse of(ConflictCluster cluster, List<String> memberObservationIds) {
        return new ClusterResponse(
                cluster.clusterId(),
                List.copyOf(memberObservationIds),
                cluster.winnerObservationId(),
                cluster.manuallyResolved(),
                cluster.resolvedObservationId());
    }
}
