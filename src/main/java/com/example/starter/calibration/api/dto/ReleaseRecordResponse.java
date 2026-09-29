package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 放行历史响应。历史不回写：预算与阈值快照为放行当时的固化值，后续阈值修订不改变本对象。
 *
 * @param batchId             放行批次 ID
 * @param releasedBy          放行人
 * @param releasedAt          放行时间（UTC）
 * @param budgetId            放行时读取的预算版本 ID；无预算为 null
 * @param calcMethod          放行时合成口径标识；无预算为 null
 * @param combinedUncertainty 放行时合成不确定度（四位小数字符串）；无预算为 null
 * @param thresholdId         放行时阈值版本行 ID 快照；无预算为 null
 * @param thresholdVersion    放行时阈值版本号快照；无预算为 null
 * @param thresholdValue      放行时阈值上限值快照；无预算为 null
 */
public record ReleaseRecordResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        Long budgetId,
        String calcMethod,
        String combinedUncertainty,
        Long thresholdId,
        Integer thresholdVersion,
        String thresholdValue) {
}
