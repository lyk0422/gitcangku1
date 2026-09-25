package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 测量血缘响应：当前测量明细 + 全部版本血缘（含已放行快照指向的版本）。
 *
 * @param measurement 当前生效测量明细
 * @param versions    全部测量版本（按版本号升序）
 */
public record MeasurementLineageResponse(
        MeasurementResponse measurement,
        List<MeasurementVersionResponse> versions) {
}
