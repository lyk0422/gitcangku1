package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 环境补偿系数版本。只增不改；生效区间为 UTC 左闭右开 [effectiveFrom, effectiveTo)。
 * 补偿值 =（实测温度 - referenceTemperature）× temperatureCoefficient
 *        +（实测湿度 - referenceHumidity）× humidityCoefficient。
 *
 * @param id                     系数版本 ID（自增）
 * @param versionTag             版本标签，全局唯一
 * @param referenceTemperature   参考温度（℃）
 * @param referenceHumidity      参考湿度（%RH）
 * @param temperatureCoefficient 温度系数（每 1℃ 的补偿量）
 * @param humidityCoefficient    湿度系数（每 1%RH 的补偿量）
 * @param effectiveFrom          生效起点（UTC，含）
 * @param effectiveTo            生效终点（UTC，不含）
 * @param createdAt              创建时间（UTC）
 */
public record CoefficientVersion(
        long id,
        String versionTag,
        BigDecimal referenceTemperature,
        BigDecimal referenceHumidity,
        BigDecimal temperatureCoefficient,
        BigDecimal humidityCoefficient,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant createdAt) {
}
