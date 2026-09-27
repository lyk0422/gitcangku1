package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行。
 * 同时固化放行时刻的预算版本、合成不确定度与阈值快照，后续阈值/预算修订不影响本记录。
 *
 * @param id                  放行记录 ID（自增）
 * @param batchId             放行批次 ID（UUID），同一批原子放行共享
 * @param measurementId       测量记录 ID
 * @param releasedBy          放行人（X-Actor-Id）
 * @param releasedAt          放行时间（UTC）
 * @param budgetVersion       放行时读取的不确定度预算版本号
 * @param combinedUncertainty 放行时合成不确定度（RSS，HALF_UP 4 位小数）
 * @param thresholdVersionId  放行时生效的阈值版本记录 ID（快照）
 * @param thresholdValue      放行时阈值取值（快照）
 * @param calculationMethod   合成口径标识
 */
public record ReleaseRecord(
        long id,
        String batchId,
        long measurementId,
        String releasedBy,
        Instant releasedAt,
        long budgetVersion,
        BigDecimal combinedUncertainty,
        long thresholdVersionId,
        BigDecimal thresholdValue,
        String calculationMethod) {

    /** 合成口径标识：平方和开根后 HALF_UP 保留 4 位小数。 */
    public static final String METHOD_RSS_HALF_UP_4 = "RSS_HALF_UP_4";
}
