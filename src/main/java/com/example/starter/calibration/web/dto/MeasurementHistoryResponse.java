package com.example.starter.calibration.web.dto;

import com.example.starter.calibration.service.MeasurementService;

/**
 * 测量历史明细响应：测量本体、放行历史（证书撤销后仍保留）、证书当前是否已撤销。
 */
public record MeasurementHistoryResponse(
        MeasurementResponse measurement,
        ReleaseRecordResponse release,
        boolean certificateRevoked) {

    public static MeasurementHistoryResponse from(MeasurementService.MeasurementHistory history) {
        return new MeasurementHistoryResponse(
                MeasurementResponse.from(history.measurement()),
                history.release() == null ? null : ReleaseRecordResponse.from(history.release()),
                history.certificateRevoked());
    }
}
