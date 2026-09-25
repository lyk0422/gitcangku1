package com.example.starter.calibration.api.dto;

/**
 * 测量修订请求。修订产生同一 measurementKey 的新版本；旧版本复核仅保留历史，不迁移。
 *
 * @param reading    新的原始读数，十进制字符串
 * @param lowerLimit 新的合格下限（含端点）
 * @param upperLimit 新的合格上限（含端点）
 */
public record ReviseMeasurementRequest(
        String reading,
        String lowerLimit,
        String upperLimit) {
}
