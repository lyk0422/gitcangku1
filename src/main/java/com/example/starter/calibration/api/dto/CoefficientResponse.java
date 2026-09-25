package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 环境补偿系数版本响应。
 *
 * @param id           版本 ID（测量快照此 ID）
 * @param instrumentModel 仪器型号
 * @param versionNo    版本号
 * @param k0           补偿常数项字符串
 * @param kTemperature  温度线性系数字符串
 * @param kHumidity    湿度线性系数字符串
 * @param tempMin      适用温度下限字符串（摄氏度）
 * @param tempMax      适用温度上限字符串（摄氏度）
 * @param humidityMin   适用湿度下限字符串（%RH）
 * @param humidityMax   适用湿度上限字符串（%RH）
 * @param createdAt     版本创建时间（UTC）
 */
public record CoefficientResponse(
        long id,
        String instrumentModel,
        int versionNo,
        String k0,
        String kTemperature,
        String kHumidity,
        String tempMin,
        String tempMax,
        String humidityMin,
        String humidityMax,
        Instant createdAt) {
}
