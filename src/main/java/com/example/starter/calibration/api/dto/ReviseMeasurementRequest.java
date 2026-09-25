package com.example.starter.calibration.api.dto;

/**
 * 修订测量请求。修订后测量回到待放行状态、修订版本号 +1，
 * 旧版本复核仅保留历史，不自动迁移给新版本。
 *
 * @param measuredAt  测量时刻，ISO-8601（按 UTC 归一）
 * @param reading     原始读数，十进制字符串
 * @param lowerLimit  合格下限（含端点），十进制字符串
 * @param upperLimit  合格上限（含端点），十进制字符串
 * @param submittedBy 修订提交人（成为新版本的提交人）
 */
public record ReviseMeasurementRequest(
        String measuredAt,
        String reading,
        String lowerLimit,
        String upperLimit,
        String submittedBy) {
}
