package com.example.starter.observation;

import java.time.Instant;

/**
 * 冲突簇：统一坐标球面距离不超过 50 米且采集时刻差不超过 60 秒的观测构成同簇。
 *
 * @param clusterId            冲突簇唯一标识
 * @param manuallyResolved     是否已人工裁决：true 时胜出记录不被自动覆盖
 * @param winnerObservationId  当前胜出观测标识
 * @param resolvedBy           人工裁决操作者标识；未人工裁决为 null
 * @param resolvedAtUtc        人工裁决时刻（UTC）；未人工裁决为 null
 */
public record GeoCluster(
        String clusterId,
        boolean manuallyResolved,
        String winnerObservationId,
        String resolvedBy,
        Instant resolvedAtUtc) {
}
