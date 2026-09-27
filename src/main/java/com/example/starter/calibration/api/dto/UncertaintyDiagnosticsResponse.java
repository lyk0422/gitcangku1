package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 不确定度诊断响应：返回实际值（合成不确定度）、要求值（阈值）与差额，
 * 以及各分量贡献度。阈值缺失时 thresholdVersion/thresholdValue/margin/withinThreshold 为 null。
 *
 * @param measurementKey      业务测量键
 * @param measurementVersion  当前测量版本
 * @param calcSpec            合成不确定度计算口径
 * @param componentCount      分量数量
 * @param combinedUncertainty 实际值：合成不确定度（4 位小数十进制字符串）
 * @param thresholdVersion    当前仪器阈值版本；无阈值时为 null
 * @param thresholdValue      要求值：阈值数值（十进制字符串）；无阈值时为 null
 * @param margin              差额：阈值 − 合成不确定度（十进制字符串，可为负）；无阈值时为 null
 * @param withinThreshold     合成不确定度是否不超过阈值；无阈值时为 null
 * @param contributions       各分量贡献度（按名称升序）
 */
public record UncertaintyDiagnosticsResponse(
        String measurementKey,
        int measurementVersion,
        String calcSpec,
        int componentCount,
        String combinedUncertainty,
        Integer thresholdVersion,
        String thresholdValue,
        String margin,
        Boolean withinThreshold,
        List<ComponentContribution> contributions) {
}
