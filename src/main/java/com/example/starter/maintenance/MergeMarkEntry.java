package com.example.starter.maintenance;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 合并快照中的单条维护标记证据：合并时刻固化，后续窗口变更与人工裁决均不改写。
 *
 * @param submissionId    观测提交唯一标识
 * @param stationId       观测站标识
 * @param maintenanceFlag 维护标记：NONE / MAINTENANCE_AFFECTED
 * @param windowId        提交时刻命中的维护窗口标识；未命中不返回
 * @param windowVersion   命中窗口的维护版本；未命中不返回
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergeMarkEntry(
        String submissionId,
        String stationId,
        String maintenanceFlag,
        String windowId,
        Integer windowVersion) {

    public static MergeMarkEntry of(StationObservationRecord record) {
        return new MergeMarkEntry(record.submissionId(), record.stationId(),
                record.maintenanceFlag(), record.windowId(), record.windowVersion());
    }
}
