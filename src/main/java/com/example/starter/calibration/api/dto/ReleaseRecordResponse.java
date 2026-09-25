package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 放行历史响应。
 *
 * @param batchId              放行批次 ID
 * @param measurementVersionNo 放行时固化的测量版本号
 * @param releasedBy           放行人
 * @param releasedAt           放行时间（UTC）
 */
public record ReleaseRecordResponse(
        String batchId,
        int measurementVersionNo,
        String releasedBy,
        Instant releasedAt) {
}
