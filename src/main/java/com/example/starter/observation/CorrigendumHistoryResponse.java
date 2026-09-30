package com.example.starter.observation;

import java.util.List;

/**
 * 更正附录版本链视图：以只读原始快照（版本 0）为起点，串联每次更正的操作者、时刻与字段差异，
 * 并给出每个版本当时的有效值。
 *
 * @param observationId   观测记录唯一标识
 * @param resolvedCluster 该观测簇是否已经冲突裁决解决
 * @param versions        按版本号先后排列的版本视图（首项为原始快照）
 */
public record CorrigendumHistoryResponse(
        String observationId,
        boolean resolvedCluster,
        List<CorrigendumVersionView> versions) {
}
