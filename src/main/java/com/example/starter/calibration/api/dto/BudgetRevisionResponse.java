package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算修订历史项。
 *
 * @param measurementVersion  修订后的测量版本
 * @param combinedUncertainty 修订后合成不确定度（4 位小数十进制字符串）
 * @param thresholdVersion    修订时仪器阈值版本
 * @param changedBy           修改人
 * @param requestId           幂等请求 ID
 * @param changedAt           修订时间（UTC）
 * @param components          修订后分量快照（按名称升序）
 */
public record BudgetRevisionResponse(
        int measurementVersion,
        String combinedUncertainty,
        int thresholdVersion,
        String changedBy,
        String requestId,
        Instant changedAt,
        List<UncertaintyComponentView> components) {
}
