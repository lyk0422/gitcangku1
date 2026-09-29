package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行。
 * 同时固化当次放行读取的预算版本、合成不确定度、计算口径与阈值版本快照；
 * 阈值后续修订不改写本记录。无预算测量放行时预算快照字段为 null（空预算，合成不确定度视为 0）。
 *
 * @param id                  放行记录 ID（自增）
 * @param batchId             放行批次 ID（UUID），同一批原子放行共享
 * @param measurementId       测量记录 ID
 * @param releasedBy          放行人（X-Actor-Id）
 * @param releasedAt          放行时间（UTC）
 * @param budgetId            放行时读取的预算版本 ID；无预算为 null
 * @param method              放行时合成口径标识；无预算为 null
 * @param combinedUncertainty 放行时未舍入合成不确定度快照；无预算为 null
 * @param thresholdId         放行时阈值版本行 ID 快照；无预算为 null
 * @param thresholdVersion    放行时阈值版本号快照；无预算为 null
 * @param thresholdValue      放行时阈值上限值快照；无预算为 null
 */
public record ReleaseRecord(
        long id,
        String batchId,
        long measurementId,
        String releasedBy,
        Instant releasedAt,
        Long budgetId,
        UncertaintyMethod method,
        BigDecimal combinedUncertainty,
        Long thresholdId,
        Integer thresholdVersion,
        BigDecimal thresholdValue) {
}
