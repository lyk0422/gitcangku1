package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测提交响应：观测版本信息连同原始坐标、统一基准坐标与簇归属。
 *
 * @param observationId        观测记录唯一标识
 * @param version              观测记录版本号（提交后固定为 1）
 * @param deviceId             提交设备标识
 * @param originalFrameVersion 原始基准版本（不可改写）
 * @param currentFrameVersion  当前生效基准版本
 * @param rawLatitude          原始纬度（度）
 * @param rawLongitude         原始经度（度）
 * @param unifiedLatitude      统一基准纬度（度）
 * @param unifiedLongitude     统一基准经度（度）
 * @param capturedAt           采集时刻（UTC，ISO-8601）
 * @param clusterId            所属冲突簇标识；无冲突时为空
 * @param location             观测地点
 * @param reading              观测读数
 * @param note                 观测备注
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmissionResponse(
        String observationId,
        int version,
        String deviceId,
        String originalFrameVersion,
        String currentFrameVersion,
        double rawLatitude,
        double rawLongitude,
        double unifiedLatitude,
        double unifiedLongitude,
        Instant capturedAt,
        String clusterId,
        String location,
        String reading,
        String note) {

    /**
     * 由观测快照与坐标信息构造响应。
     */
    public static SubmissionResponse of(ObservationSnapshot snapshot, ObservationGeo geo) {
        return new SubmissionResponse(
                snapshot.observationId(),
                snapshot.version(),
                geo.deviceId(),
                geo.originalFrameVersion(),
                geo.currentFrameVersion(),
                geo.rawLatitude(),
                geo.rawLongitude(),
                geo.unifiedLatitude(),
                geo.unifiedLongitude(),
                geo.capturedAt(),
                geo.clusterId(),
                snapshot.location(),
                snapshot.reading(),
                snapshot.note());
    }
}
