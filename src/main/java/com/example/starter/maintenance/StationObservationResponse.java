package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 站点观测原始记录响应：携带提交时刻固化的维护标记与命中窗口版本。
 *
 * @param submissionId    观测提交唯一标识
 * @param clusterId       冲突簇标识
 * @param stationId       观测站标识
 * @param observedAtUtc   观测时刻（UTC）
 * @param submittedAtUtc  提交受理时刻（UTC）
 * @param location        观测地点
 * @param reading         观测读数
 * @param note            观测备注
 * @param maintenanceFlag 维护标记：NONE / MAINTENANCE_AFFECTED
 * @param windowId        提交时刻命中的维护窗口标识；未命中不返回
 * @param windowVersion   命中窗口的维护版本；未命中不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StationObservationResponse(
        String submissionId,
        String clusterId,
        String stationId,
        Instant observedAtUtc,
        Instant submittedAtUtc,
        String location,
        String reading,
        String note,
        String maintenanceFlag,
        String windowId,
        Integer windowVersion) {

    public static StationObservationResponse of(StationObservationRecord record) {
        return new StationObservationResponse(record.submissionId(), record.clusterId(), record.stationId(),
                record.observedAtUtc(), record.submittedAtUtc(), record.location(), record.reading(), record.note(),
                record.maintenanceFlag(), record.windowId(), record.windowVersion());
    }
}
