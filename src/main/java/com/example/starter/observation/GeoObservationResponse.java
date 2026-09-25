package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 坐标观测响应：同时返回原始坐标（含原基准版本）与统一基准坐标。
 *
 * @param observationId       坐标观测唯一标识
 * @param deviceId            提交设备唯一标识
 * @param rawLatitude         原始纬度（度）
 * @param rawLongitude        原始经度（度）
 * @param rawFrameVersion     提交时携带的原基准版本
 * @param unifiedLatitude     统一基准纬度（度）
 * @param unifiedLongitude    统一基准经度（度）
 * @param appliedFrameVersion 当前统一坐标所采用的基准参数版本
 * @param capturedAt          采集时刻（UTC）
 * @param location            观测地点
 * @param reading             观测读数
 * @param note                观测备注
 * @param clusterId           所属冲突簇标识；不属于任何簇时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeoObservationResponse(
        String observationId,
        String deviceId,
        double rawLatitude,
        double rawLongitude,
        String rawFrameVersion,
        double unifiedLatitude,
        double unifiedLongitude,
        String appliedFrameVersion,
        Instant capturedAt,
        String location,
        String reading,
        String note,
        String clusterId) {

    public static GeoObservationResponse of(GeoObservation observation) {
        return new GeoObservationResponse(
                observation.observationId(), observation.deviceId(),
                observation.rawLatitude(), observation.rawLongitude(), observation.rawFrameVersion(),
                observation.unifiedLatitude(), observation.unifiedLongitude(), observation.appliedFrameVersion(),
                observation.capturedAtUtc(),
                observation.location(), observation.reading(), observation.note(), observation.clusterId());
    }
}
