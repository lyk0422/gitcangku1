package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 测量版本历史响应。
 *
 * @param measurementKey 业务测量键
 * @param latestRevision 当前最新修订版本号
 * @param revisions      全部版本明细（按版本号升序）
 */
public record MeasurementHistoryResponse(
        String measurementKey,
        int latestRevision,
        List<MeasurementResponse> revisions) {
}
