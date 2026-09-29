package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行结论快照响应：放行时刻固化的测量结果、预算、计算口径与阈值版本。
 *
 * @param batchId             放行批次 ID
 * @param measurementId       放行的测量记录（版本行）ID
 * @param measurementKey      放行时测量业务键
 * @param measurementVersion  放行时测量版本号
 * @param computedValue       放行时未舍入计算值（十进制字符串）
 * @param passedBias          放行时偏差判定
 * @param budgetVersion       放行时预算版本号（无分量为 0）
 * @param combinedUncertainty 放行时合成不确定度（4 位小数字符串）
 * @param formula             计算口径标识
 * @param thresholdId         放行时阈值版本行 ID
 * @param thresholdVersion    放行时阈值版本号
 * @param thresholdValue      放行时阈值要求值（十进制字符串）
 * @param uncertaintyOk       放行时合成不确定度是否不超过阈值
 * @param decision            放行结论 APPROVED
 * @param releasedBy          放行人
 * @param releasedAt          放行时间（UTC，微秒精度）
 * @param components          放行时按名称升序固化的分量明细
 */
public record ReleaseSnapshotResponse(
        String batchId,
        long measurementId,
        String measurementKey,
        int measurementVersion,
        String computedValue,
        boolean passedBias,
        int budgetVersion,
        String combinedUncertainty,
        String formula,
        long thresholdId,
        int thresholdVersion,
        String thresholdValue,
        boolean uncertaintyOk,
        String decision,
        String releasedBy,
        Instant releasedAt,
        List<ComponentResponse> components) {
}
