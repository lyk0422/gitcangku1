package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行历史响应。放行时固化的不确定度预算快照随记录返回，后续阈值修订不改写。
 *
 * @param batchId             放行批次 ID
 * @param releasedBy          放行人
 * @param releasedAt          放行时间（UTC）
 * @param combinedUncertainty 放行时合成不确定度快照（4 位小数十进制字符串）
 * @param thresholdVersion    放行时阈值版本快照
 * @param thresholdValue      放行时阈值数值快照（十进制字符串）
 * @param budgetVersion       放行时测量版本（预算版本）快照
 * @param calcSpec            合成不确定度计算口径快照
 * @param components          放行时分量快照（按名称升序）
 */
public record ReleaseRecordResponse(
        String batchId,
        String releasedBy,
        Instant releasedAt,
        String combinedUncertainty,
        int thresholdVersion,
        String thresholdValue,
        int budgetVersion,
        String calcSpec,
        List<UncertaintyComponentView> components) {
}
