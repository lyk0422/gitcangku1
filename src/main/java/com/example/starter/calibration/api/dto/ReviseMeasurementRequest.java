package com.example.starter.calibration.api.dto;

/**
 * 修订测量请求。读数与上下限为最多 6 位小数的十进制字符串；
 * 仪器与测量时刻不可修订，沿用首版值。
 *
 * @param expectedRevision 期望的当前最新修订号；与实际不一致时返回 409
 * @param requestId        修订幂等键；同键同参重放返回首次结果，改参返回 409
 * @param reason           修订原因，非空
 * @param reading          新原始读数，十进制字符串
 * @param lowerLimit       新合格下限（含端点），十进制字符串
 * @param upperLimit       新合格上限（含端点），十进制字符串
 */
public record ReviseMeasurementRequest(
        Integer expectedRevision,
        String requestId,
        String reason,
        String reading,
        String lowerLimit,
        String upperLimit) {
}
