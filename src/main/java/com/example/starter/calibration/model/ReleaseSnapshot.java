package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 放行结论快照。放行时刻固化测量结果、不确定度预算、计算口径与阈值版本；
 * 后续阈值修订、预算修改或测量修订均不改写旧快照（旧证据不可回写）。
 *
 * @param id                  快照 ID（自增）
 * @param batchId             放行批次 ID（UUID）
 * @param measurementId       放行的测量记录（版本行）ID
 * @param measurementKey      放行时测量业务键（固化）
 * @param measurementVersion  放行时测量版本号（固化）
 * @param computedValue       放行时未舍入计算值（固化）
 * @param passedBias          放行时偏差判定结果（固化）
 * @param budgetVersion       放行时读取的预算版本号（固化）；无分量预算时为 0
 * @param combinedUncertainty 放行时合成不确定度，RSS 口径 HALF_UP 4 位小数（固化）
 * @param formula             计算口径标识：RSS_ROOT_SUM_OF_SQUARES
 * @param thresholdId         放行时采用的阈值版本行 ID（固化）
 * @param thresholdVersion    放行时阈值版本号（固化）
 * @param thresholdValue      放行时阈值要求值（固化）
 * @param uncertaintyOk       放行时合成不确定度是否不超过阈值（固化）
 * @param decision            放行结论：APPROVED
 * @param releasedBy          放行人（固化）
 * @param releasedAt          放行时间（UTC，微秒精度）
 * @param components          放行时按名称升序固化的分量明细
 */
public record ReleaseSnapshot(
        long id,
        String batchId,
        long measurementId,
        String measurementKey,
        int measurementVersion,
        BigDecimal computedValue,
        boolean passedBias,
        int budgetVersion,
        BigDecimal combinedUncertainty,
        String formula,
        long thresholdId,
        int thresholdVersion,
        BigDecimal thresholdValue,
        boolean uncertaintyOk,
        String decision,
        String releasedBy,
        Instant releasedAt,
        List<UncertaintyComponent> components) {
}
