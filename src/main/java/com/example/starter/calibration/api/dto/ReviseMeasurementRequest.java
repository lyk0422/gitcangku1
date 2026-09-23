package com.example.starter.calibration.api.dto;

/**
 * 测量修订请求。修订只改读数与合格上下限；仪器与测量 UTC 时刻沿用第 1 版。
 * 请求头 X-Actor-Id 为原提交人，并携带 requestId、expectedRevision 与非空修订原因。
 *
 * @param measurementKey   业务测量键
 * @param expectedRevision 期望基准版本号；须等于当前最新版本
 * @param requestId        修订请求 ID，用于同键幂等重放
 * @param reason           修订原因（非空）
 * @param reading          修订后的原始读数，十进制字符串（最多 6 位小数）
 * @param lowerLimit       修订后的合格下限（含端点），十进制字符串
 * @param upperLimit       修订后的合格上限（含端点），十进制字符串
 */
public record ReviseMeasurementRequest(
        String measurementKey,
        Integer expectedRevision,
        String requestId,
        String reason,
        String reading,
        String lowerLimit,
        String upperLimit) {
}
