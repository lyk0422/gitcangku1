package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 单条观测提交结果：始终返回原始记录及其固化的维护标记。
 *
 * @param observationId             观测记录唯一标识
 * @param stationId                 所属观测站标识
 * @param clusterId                 所属冲突簇标识；未归簇为 null
 * @param submittedAtUtc            观测提交时刻（UTC）
 * @param payload                   观测原始内容原文
 * @param maintenanceAffected       是否命中维护窗口
 * @param matchedWindowId           命中的维护窗口标识；未命中为 null
 * @param matchedMaintenanceVersion 命中窗口的维护版本号；未命中为 null
 */
public record ObservationResultResponse(
        String observationId,
        String stationId,
        String clusterId,
        Instant submittedAtUtc,
        String payload,
        boolean maintenanceAffected,
        String matchedWindowId,
        Integer matchedMaintenanceVersion) {

    /**
     * 由领域对象构造响应。
     */
    public static ObservationResultResponse of(StationObservation observation) {
        return new ObservationResultResponse(
                observation.observationId(),
                observation.stationId(),
                observation.clusterId(),
                observation.submittedAtUtc(),
                observation.payload(),
                observation.maintenanceAffected(),
                observation.matchedWindowId(),
                observation.matchedMaintenanceVersion());
    }
}
