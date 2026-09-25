package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 设备基准修改响应：携带重算结果摘要；未发生簇变化时 recalcId 为空。
 *
 * @param deviceId     设备唯一标识
 * @param frameVersion 修改后的设备基准版本
 * @param recalced     本次是否因簇成员或胜出记录变化写入了重算记录
 * @param recalcId     重算记录标识；无变化时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceFrameResponse(
        String deviceId,
        String frameVersion,
        boolean recalced,
        String recalcId) {
}
