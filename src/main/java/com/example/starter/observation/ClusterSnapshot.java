package com.example.starter.observation;

import java.util.List;

/**
 * 冲突簇快照：基准重算记录中固化的新旧簇状态。
 *
 * @param clusterId            冲突簇唯一标识
 * @param memberObservationIds 簇成员观测记录标识列表（按标识排序）
 * @param winnerObservationId  快照时刻的当前胜出观测记录标识
 * @param manuallyResolved     快照时刻是否已人工裁决
 */
public record ClusterSnapshot(
        String clusterId,
        List<String> memberObservationIds,
        String winnerObservationId,
        boolean manuallyResolved) {
}
