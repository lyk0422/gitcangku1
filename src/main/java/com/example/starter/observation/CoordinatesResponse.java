package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 坐标查询响应：原始坐标与原基准版本（不可改写）连同统一基准坐标与簇归属。
 *
 * @param observationId        观测记录唯一标识
 * @param deviceId             提交设备标识
 * @param originalFrameVersion 原始基准版本（不可改写）
 * @param currentFrameVersion  当前生效基准版本
 * @param rawLatitude          原始纬度（度）
 * @param rawLongitude         原始经度（度）
 * @param unifiedLatitude      统一基准纬度（度）
 * @param unifiedLongitude     统一基准经度（度）
 * @param capturedAt           采集时刻（UTC，ISO-8601）
 * @param clusterId            所属冲突簇标识；无冲突时为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoordinatesResponse(
        String observationId,
        String deviceId,
        String originalFrameVersion,
        String currentFrameVersion,
        double rawLatitude,
        double rawLongitude,
        double unifiedLatitude,
        double unifiedLongitude,
        Instant capturedAt,
        String clusterId) {

    /**
     * 由坐标信息构造响应。
     */
    public static CoordinatesResponse of(ObservationGeo geo) {
        return new CoordinatesResponse(
                geo.observationId(),
                geo.deviceId(),
                geo.originalFrameVersion(),
                geo.currentFrameVersion(),
                geo.rawLatitude(),
                geo.rawLongitude(),
                geo.unifiedLatitude(),
                geo.unifiedLongitude(),
                geo.capturedAt(),
                geo.clusterId());
    }
}
