package com.example.starter.calibration.api.dto;

/**
 * 创建环境补偿系数版本请求。参考温湿度与系数为十进制字符串（参考值最多 6 位小数，
 * 系数最多 12 位小数），生效区间为 UTC 左闭右开。
 *
 * @param versionTag             版本标签，全局唯一
 * @param referenceTemperature   参考温度（℃）
 * @param referenceHumidity      参考湿度（%RH）
 * @param temperatureCoefficient 温度系数（每 1℃ 的补偿量）
 * @param humidityCoefficient    湿度系数（每 1%RH 的补偿量）
 * @param effectiveFrom          生效起点（含），ISO-8601
 * @param effectiveTo            生效终点（不含），ISO-8601
 */
public record CreateCoefficientVersionRequest(
        String versionTag,
        String referenceTemperature,
        String referenceHumidity,
        String temperatureCoefficient,
        String humidityCoefficient,
        String effectiveFrom,
        String effectiveTo) {
}
