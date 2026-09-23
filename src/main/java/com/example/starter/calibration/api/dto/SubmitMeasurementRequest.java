package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 *
 * @param measurementKey     业务测量键，全局唯一（幂等键）
 * @param instrumentId       仪器 ID
 * @param measuredAt         测量时刻，ISO-8601（按 UTC 归一）
 * @param reading            原始读数，十进制字符串
 * @param lowerLimit         合格下限（含端点），十进制字符串
 * @param upperLimit         合格上限（含端点），十进制字符串
 * @param submittedBy        提交人
 * @param standardVersionKey 测量绑定的标准器版本业务键；须在测量时刻有效（VALID 且窗口覆盖），可空表示不绑定标准器
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy,
        String standardVersionKey) {
}
