package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 环境补偿系数版本（仪器型号维度）。线性补偿公式：
 * compensation = k0 + kTemperature×温度(℃) + kHumidity×湿度(%RH)。
 * 版本创建后不可修改；发布新版本只影响后续测量，已固化测量快照 coefficientId 不改写。
 *
 * @param id           系数版本 ID（自增），测量快照此 ID
 * @param instrumentModel 仪器型号
 * @param versionNo    型号内版本号，从 1 递增
 * @param k0           补偿常数项
 * @param kTemperature  温度线性系数（每 1℃ 的补偿量）
 * @param kHumidity    湿度线性系数（每 1%RH 的补偿量）
 * @param tempMin      适用温度下限（含端点，摄氏度）
 * @param tempMax      适用温度上限（含端点，摄氏度）
 * @param humidityMin   适用湿度下限（含端点，%RH）
 * @param humidityMax   适用湿度上限（含端点，%RH）
 * @param createdAt    版本创建时间（UTC）
 */
public record CompensationCoefficient(
        long id,
        String instrumentModel,
        int versionNo,
        BigDecimal k0,
        BigDecimal kTemperature,
        BigDecimal kHumidity,
        BigDecimal tempMin,
        BigDecimal tempMax,
        BigDecimal humidityMin,
        BigDecimal humidityMax,
        Instant createdAt) {

    /**
     * 记录环境是否落在适用区间内（含端点）。
     */
    public boolean covers(BigDecimal temperature, BigDecimal humidity) {
        return temperature.compareTo(tempMin) >= 0
                && temperature.compareTo(tempMax) <= 0
                && humidity.compareTo(humidityMin) >= 0
                && humidity.compareTo(humidityMax) <= 0;
    }
}
