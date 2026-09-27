package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放行历史记录。证书撤销后保留，不回写为从未放行。
 * 放行时固化不确定度预算快照（分量、计算口径、阈值版本与放行结论），后续阈值修订不改写。
 *
 * @param id                  放行记录 ID（自增）
 * @param batchId             放行批次 ID（UUID），同一批原子放行共享
 * @param measurementId       测量记录 ID
 * @param releasedBy          放行人（X-Actor-Id）
 * @param releasedAt          放行时间（UTC）
 * @param combinedUncertainty 放行时合成不确定度快照（4 位小数）
 * @param thresholdVersion    放行时阈值版本快照
 * @param thresholdValue      放行时阈值数值快照
 * @param budgetVersion       放行时测量版本（预算版本）快照
 * @param calcSpec            合成不确定度计算口径快照
 * @param componentsSnapshot  放行时分量快照（JSON 数组，按分量名称排序）
 */
public record ReleaseRecord(
        long id,
        String batchId,
        long measurementId,
        String releasedBy,
        Instant releasedAt,
        BigDecimal combinedUncertainty,
        int thresholdVersion,
        BigDecimal thresholdValue,
        int budgetVersion,
        String calcSpec,
        String componentsSnapshot) {
}
