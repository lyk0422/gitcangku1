package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 设备基准版本登记/变更响应。
 *
 * @param deviceId             设备唯一标识
 * @param previousFrameVersion 变更前设备基准版本；首次登记时为空
 * @param frameVersion         变更后设备基准版本
 * @param recalculatedCount    本次重算的未人工裁决观测数量
 * @param recalcId             基准重算记录标识；重算未改变簇成员或胜出记录时为空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceFrameResponse(
        String deviceId,
        String previousFrameVersion,
        String frameVersion,
        int recalculatedCount,
        String recalcId) {
}
