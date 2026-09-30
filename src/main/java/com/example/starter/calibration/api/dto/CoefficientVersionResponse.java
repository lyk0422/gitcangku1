package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 环境补偿系数版本响应。
 *
 * @param id                     系数版本 ID
 * @param versionTag             版本标签
 * @param referenceTemperature   参考温度（℃）
 * @param referenceHumidity      参考湿度（%RH）
 * @param temperatureCoefficient 温度系数（每 1℃ 的补偿量，十进制字符串）
 * @param humidityCoefficient    湿度系数（每 1%RH 的补偿量，十进制字符串）
 * @param effectiveFrom          生效起点（UTC，含）
 * @param effectiveTo            生效终点（UTC，不含）
 * @param createdAt              创建时间（UTC）
 */
public record CoefficientVersionResponse(
        long id,
        String versionTag,
        String referenceTemperature,
        String referenceHumidity,
        String temperatureCoefficient,
        String humidityCoefficient,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant createdAt) {
}
