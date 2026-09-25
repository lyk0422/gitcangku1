package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 基准重算记录响应：固化的新旧簇快照与参数版本。
 *
 * @param recalcId         重算记录唯一标识
 * @param deviceId         被重算的设备标识
 * @param requestId        触发重算的请求标识
 * @param oldFrameVersion  重算前设备基准版本（参数版本）；首次登记时为空
 * @param newFrameVersion  重算后设备基准版本（参数版本）
 * @param oldClusters      重算前设备相关簇快照
 * @param newClusters      重算后设备相关簇快照
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrameRecalcResponse(
        String recalcId,
        String deviceId,
        String requestId,
        String oldFrameVersion,
        String newFrameVersion,
        List<ClusterSnapshot> oldClusters,
        List<ClusterSnapshot> newClusters) {

    /**
     * 由重算记录构造响应。
     */
    public static FrameRecalcResponse of(FrameRecalcRecord record) {
        return new FrameRecalcResponse(
                record.recalcId(),
                record.deviceId(),
                record.requestId(),
                record.oldFrameVersion(),
                record.newFrameVersion(),
                List.copyOf(record.oldClusters()),
                List.copyOf(record.newClusters()));
    }
}
