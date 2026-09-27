package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行。
 * 放行时固化合成不确定度、阈值取值与版本、计算口径；后续阈值修订不改写本快照。
 *
 * @param id                  放行记录 ID（自增）
 * @param batchId             放行批次 ID（UUID），同一批原子放行共享
 * @param measurementId       测量记录 ID
 * @param releasedBy          放行人（X-Actor-Id）
 * @param releasedAt          放行时间（UTC）
 * @param combinedUncertainty 放行时合成不确定度（RSS，HALF_UP 4 位小数）
 * @param thresholdValue      放行时仪器阈值取值
 * @param thresholdVersion    放行时仪器阈值版本号
 * @param formula             合成不确定度计算口径标识
 */
public record ReleaseRecord(
        long id,
        String batchId,
        long measurementId,
        String releasedBy,
        Instant releasedAt,
        BigDecimal combinedUncertainty,
        BigDecimal thresholdValue,
        int thresholdVersion,
        String formula) {
}
