package com.example.starter.calibration.api.dto;

/**
 * 提交测量请求。读数与上下限为最多 6 位小数的十进制字符串。
 *
 * <p>引用方式二选一：显式提供 standardId + certificateVersion 引用确定的证书版本；
 * 或两者皆空，按 instrumentId 与 measuredAt 自动匹配唯一在测量时刻有效的证书。
 * 显式引用时该证书版本必须在 measuredAt 时刻有效（左闭右开，端点到期即无效）且未撤销。
 *
 * @param measurementKey     业务测量键，全局唯一（幂等键）
 * @param instrumentId       仪器/被测对象 ID
 * @param standardId         显式引用的标准器 ID；为空时按仪器自动匹配
 * @param certificateVersion 显式引用的证书版本；与 standardId 同时提供
 * @param measuredAt         测量时刻，ISO-8601（按 UTC 归一）
 * @param reading            原始读数，十进制字符串
 * @param lowerLimit         合格下限（含端点），十进制字符串
 * @param upperLimit         合格上限（含端点），十进制字符串
 * @param submittedBy        提交人
 */
public record SubmitMeasurementRequest(
        String measurementKey,
        String instrumentId,
        String standardId,
        String certificateVersion,
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy) {
}
