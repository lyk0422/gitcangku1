package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批次差异只读结果：逐位置比较来源批次与重新放行后新批次采用的测量。
 *
 * @param sourceBatchId 来源批次 ID
 * @param newBatchId    新批次 ID
 * @param positions     逐位置差异（按位置升序）
 */
public record BatchDiffResponse(
        String sourceBatchId,
        String newBatchId,
        List<PositionDiff> positions) {

    /**
     * 单个位置的差异。
     *
     * @param position       位置（从 1 开始）
     * @param sourceKey      来源批次该位置测量业务键
     * @param sourceVersion  来源版本
     * @param usedKey        新批次采用测量业务键
     * @param usedVersion    采用版本
     * @param revised        是否使用了修订
     * @param sourceComputed 来源未舍入计算值（十进制字符串）
     * @param usedComputed   采用未舍入计算值（十进制字符串）
     * @param sourcePassed   来源是否合格
     * @param usedPassed     采用是否合格
     */
    public record PositionDiff(
            int position,
            String sourceKey,
            int sourceVersion,
            String usedKey,
            int usedVersion,
            boolean revised,
            String sourceComputed,
            String usedComputed,
            boolean sourcePassed,
            boolean usedPassed) {
    }
}
