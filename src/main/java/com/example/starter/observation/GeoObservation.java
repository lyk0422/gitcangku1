package com.example.starter.observation;

import java.time.Instant;

/**
 * 坐标观测：设备提交的带坐标观测记录。原始坐标与原基准版本落库后不可改写；
 * 统一基准坐标与簇归属可在基准变更重算时更新。
 *
 * @param observationId       坐标观测唯一标识（服务端生成）
 * @param deviceId            提交设备唯一标识
 * @param requestId           生成该观测的请求标识
 * @param rawLatitude         原始纬度（度，不可改写）
 * @param rawLongitude        原始经度（度，不可改写）
 * @param rawFrameVersion     提交时携带的原基准版本（不可改写）
 * @param unifiedLatitude     统一基准纬度（度）
 * @param unifiedLongitude    统一基准经度（度）
 * @param appliedFrameVersion 当前统一坐标所采用的基准参数版本
 * @param capturedAtUtc       采集时刻（UTC）
 * @param location            观测地点（字段内容）
 * @param reading             观测读数（字段内容，十进制字符串）
 * @param note                观测备注（字段内容）
 * @param clusterId           所属冲突簇标识；null 表示不属于任何簇
 */
public record GeoObservation(
        String observationId,
        String deviceId,
        String requestId,
        double rawLatitude,
        double rawLongitude,
        String rawFrameVersion,
        double unifiedLatitude,
        double unifiedLongitude,
        String appliedFrameVersion,
        Instant capturedAtUtc,
        String location,
        String reading,
        String note,
        String clusterId) {
}
