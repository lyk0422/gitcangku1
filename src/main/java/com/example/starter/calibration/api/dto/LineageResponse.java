package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 测量血缘响应：全部版本按版本号升序，current 标记当前有效版本。
 *
 * @param measurementKey 测量键
 * @param currentVersion 当前有效版本号
 * @param versions       版本历史
 */
public record LineageResponse(
        String measurementKey,
        int currentVersion,
        List<MeasurementVersionResponse> versions) {
}
