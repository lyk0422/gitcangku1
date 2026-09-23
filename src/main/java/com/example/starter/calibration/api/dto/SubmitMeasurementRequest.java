package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 *
 * @param measurementKey 业务测量键，全局唯一（幂等键）
 * @param instrumentId   仪器 ID
 * @param measuredAt     测量时刻，ISO-8601（按 UTC 归一）
 * @param reading        原始读数，十进制字符串
 * @param lowerLimit     合格下限（含端点），十进制字符串
 * @param upperLimit     合格上限（含端点），十进制字符串
 * @param submittedBy    提交人
 * @param standardId     标准器版本业务键；提供时须存在当时有效的 VALID 版本，否则 422；缺省为 null 不绑定
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        String standardId) {
}
