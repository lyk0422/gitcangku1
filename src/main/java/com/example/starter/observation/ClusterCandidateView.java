package com.example.starter.observation;

import java.time.Instant;

/**
 * 簇候选预览项：一条活跃、未归并、未删除记录在预览时刻的只读快照。
 *
 * @param recordKey  候选记录键
 * @param generation 预览冻结的 generation（当前 version）
 * @param deviceId   采集设备标识（可能为 null）
 * @param observedAt 观测发生时刻（UTC）
 * @param location   地点字段值
 * @param reading    读数字段值（十进制原文）
 * @param note       备注字段值
 */
public record ClusterCandidateView(
        String recordKey,
        int generation,
        String deviceId,
        Instant observedAt,
        String location,
        String reading,
        String note) {

    public static ClusterCandidateView of(ObservationSnapshot snapshot) {
        return new ClusterCandidateView(
                snapshot.observationId(),
                snapshot.version(),
                snapshot.deviceId(),
                snapshot.observedAt(),
                snapshot.location(),
                snapshot.reading(),
                snapshot.note());
    }
}
