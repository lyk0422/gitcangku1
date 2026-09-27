package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 不确定度预算明细响应。combinedUncertainty 为平方和开根后 HALF_UP 4 位小数值；
 * 阈值缺失时 thresholdVersion/thresholdValue/withinThreshold 为 null。
 *
 * @param measurementKey      业务测量键
 * @param measurementVersion  当前测量版本
 * @param status              测量状态：PENDING / RELEASED
 * @param calcSpec            合成不确定度计算口径
 * @param combinedUncertainty 合成不确定度（4 位小数十进制字符串）
 * @param thresholdVersion    当前仪器阈值版本；无阈值时为 null
 * @param thresholdValue      当前阈值数值（十进制字符串）；无阈值时为 null
 * @param withinThreshold     合成不确定度是否不超过阈值；无阈值时为 null
 * @param components          分量列表（按名称升序）
 * @param budgetUpdatedAt     最近一次预算修订时间（UTC）；从未修订时为 null
 */
public record UncertaintyBudgetResponse(
        String measurementKey,
        int measurementVersion,
        String status,
        String calcSpec,
        String combinedUncertainty,
        Integer thresholdVersion,
        String thresholdValue,
        Boolean withinThreshold,
        List<UncertaintyComponentView> components,
        Instant budgetUpdatedAt) {
}
