package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求（普通模式）。读数与上下限为最多 6 位小数的十进制字符串。
 *
 * @param measurementKey  业务测量键，全局唯一（幂等键）
 * @param instrumentId    仪器 ID
 * @param measuredAt      测量时刻，ISO-8601（按 UTC 归一）
 * @param reading         原始读数，十进制字符串
 * @param lowerLimit      合格下限（含端点），十进制字符串
 * @param upperLimit      合格上限（含端点），十进制字符串
 * @param submittedBy     提交人
 * @param requestId       全局请求 ID（可选）；提供时启用幂等去重
 * @param expectedVersion 期望版本（可选），仅作为幂等身份组成部分
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        String requestId,
        String expectedVersion) {
}
