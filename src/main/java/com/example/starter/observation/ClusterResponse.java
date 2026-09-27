package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 冲突簇响应：返回簇的登记快照、状态与裁决信息。
 *
 * @param clusterId        簇唯一标识
 * @param batchRequestId   产生该簇的批量请求标识
 * @param observationId    冲突所属观测标识
 * @param baseVersion      入簇时基线版本号
 * @param candidateLocation 入簇候选地点快照
 * @param candidateReading 入簇候选读数快照
 * @param candidateNote    入簇候选备注快照
 * @param conflictFields   入簇时重算出的冲突字段名列表
 * @param status           簇状态 PENDING/RESOLVED
 * @param resolutionId     裁决写入的 conflict_resolution 标识；PENDING 时为 null
 * @param resolvedAtUtc    裁决完成时刻（UTC）；PENDING 时为 null
 */
public record ClusterResponse(
        String clusterId,
        String batchRequestId,
        String observationId,
        int baseVersion,
        String candidateLocation,
        String candidateReading,
        String candidateNote,
        List<String> conflictFields,
        String status,
        String resolutionId,
        Instant resolvedAtUtc) {

    public static ClusterResponse of(MergeCluster cluster) {
        return new ClusterResponse(
                cluster.clusterId(),
                cluster.batchRequestId(),
                cluster.observationId(),
                cluster.baseVersion(),
                cluster.candidateLocation(),
                cluster.candidateReading(),
                cluster.candidateNote(),
                List.copyOf(cluster.conflictFields()),
                cluster.status(),
                cluster.resolutionId(),
                cluster.resolvedAtUtc());
    }
}
