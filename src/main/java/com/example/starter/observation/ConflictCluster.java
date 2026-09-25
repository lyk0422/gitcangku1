package com.example.starter.observation;

/**
 * 冲突簇：对应 conflict_cluster 表的一行。
 *
 * @param clusterId              冲突簇唯一标识
 * @param winnerObservationId    当前胜出观测记录标识
 * @param manuallyResolved       是否已人工裁决：true 时选择结论不被自动覆盖
 * @param resolvedObservationId  人工裁决选定的观测记录标识（裁决后不可变）
 */
public record ConflictCluster(
        String clusterId,
        String winnerObservationId,
        boolean manuallyResolved,
        String resolvedObservationId) {
}
