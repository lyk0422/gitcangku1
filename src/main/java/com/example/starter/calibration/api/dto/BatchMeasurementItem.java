package com.example.starter.calibration.api.dto;

/**
 * 批量测量提交中的单条测量。
 *
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param referenceKey   幂等引用键，可缺省；同键重放返回已有结果，失败不占键
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻，ISO-8601（按 UTC 归一）
 * @param reading        原始读数，十进制字符串
 * @param lowerLimit     合格下限（含端点），十进制字符串
 * @param upperLimit     合格上限（含端点），十进制字符串
 * @param submittedBy    提交人
 */
public record BatchMeasurementItem(
        String measurementKey,
        String referenceKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy) {
}
