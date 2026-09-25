package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测坐标信息：对应 observation_geo 表的一行。
 * 原始坐标与原基准版本不可改写；统一基准坐标与当前基准版本在基准重算时更新。
 *
 * @param observationId        观测记录唯一标识
 * @param submissionSeq        提交顺序号，全局递增，用于胜出判定的稳定次序
 * @param deviceId             提交设备标识
 * @param originalFrameVersion 提交时的原始基准版本（不可改写）
 * @param currentFrameVersion  当前生效基准版本（基准重算时更新）
 * @param rawLatitude          原始纬度（度，不可改写）
 * @param rawLongitude         原始经度（度，不可改写）
 * @param unifiedLatitude      统一基准纬度（度）
 * @param unifiedLongitude     统一基准经度（度）
 * @param capturedAt           采集时刻（UTC）
 * @param clusterId            所属冲突簇标识；null 表示不在任何冲突簇中
 */
public record ObservationGeo(
        String observationId,
        long submissionSeq,
        String deviceId,
        String originalFrameVersion,
        String currentFrameVersion,
        double rawLatitude,
        double rawLongitude,
        double unifiedLatitude,
        double unifiedLongitude,
        Instant capturedAt,
        String clusterId) {
}
