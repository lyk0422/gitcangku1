package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 观测记录响应：普通版本携带全部业务字段与许可信息；删除墓碑版本只返回删除状态和版本，不带业务字段。
 *
 * @param observationId  观测记录唯一标识
 * @param version        版本号
 * @param deleted        是否为删除墓碑
 * @param location       观测地点（墓碑版本不返回）
 * @param reading        观测读数（墓碑版本不返回）
 * @param note           观测备注（墓碑版本不返回）
 * @param deviceId       采集设备标识（墓碑版本不返回）
 * @param gridX          采集网格 X 坐标（墓碑版本不返回）
 * @param gridY          采集网格 Y 坐标（墓碑版本不返回）
 * @param collectedAtUtc 采集时刻（UTC，墓碑版本不返回）
 * @param permitId       提交时命中的采样许可标识（墓碑版本不返回）
 * @param permitVersion  提交时命中的许可版本（墓碑版本不返回）
 * @param reviewStatus   复核标记：NONE / PERMIT_REVIEW（仅当前状态查询返回）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ObservationResponse(
        String observationId,
        int version,
        boolean deleted,
        String location,
        String reading,
        String note,
        String deviceId,
        Integer gridX,
        Integer gridY,
        Instant collectedAtUtc,
        String permitId,
        Integer permitVersion,
        String reviewStatus) {

    /**
     * 由快照构造响应；墓碑版本自动省略业务字段与许可信息。
     */
    public static ObservationResponse of(ObservationSnapshot snapshot) {
        if (snapshot.deleted()) {
            return new ObservationResponse(snapshot.observationId(), snapshot.version(), true,
                    null, null, null, null, null, null, null, null, null, null);
        }
        return new ObservationResponse(snapshot.observationId(), snapshot.version(), false,
                snapshot.location(), snapshot.reading(), snapshot.note(),
                snapshot.deviceId(), snapshot.gridX(), snapshot.gridY(), snapshot.collectedAtUtc(),
                snapshot.permitId(), snapshot.permitVersion(), snapshot.reviewStatus());
    }
}
