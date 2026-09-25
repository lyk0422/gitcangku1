package com.example.starter.observation;

import java.util.List;

/**
 * 簇快照：重算记录中固化的新旧簇状态（簇标识、成员、胜出观测）。
 *
 * @param clusterId            冲突簇唯一标识
 * @param memberObservationIds 簇成员观测标识（按标识排序）
 * @param winnerObservationId  快照时刻的胜出观测标识
 */
public record ClusterSnapshot(
        String clusterId,
        List<String> memberObservationIds,
        String winnerObservationId) {
}
