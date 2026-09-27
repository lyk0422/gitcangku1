package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 放行历史响应（含放行时刻固化的预算与阈值快照）。
 *
 * @param batchId             放行批次 ID
 * @param releasedBy          放行人
 * @param releasedAt          放行时间（UTC）
 * @param budgetVersion       放行时读取的预算版本号
 * @param combinedUncertainty 放行时合成不确定度（RSS，HALF_UP 4 位）
 * @param thresholdVersionId  放行时生效的阈值版本 ID
 * @param thresholdValue      放行时阈值取值（快照）
 * @param calculationMethod   合成口径标识
 */
public record ReleaseRecordResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        long budgetVersion,
        String combinedUncertainty,
        long thresholdVersionId,
        String thresholdValue,
        String calculationMethod) {
}
