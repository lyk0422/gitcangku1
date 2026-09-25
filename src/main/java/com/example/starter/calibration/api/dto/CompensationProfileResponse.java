package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 环境补偿系数版本响应。
 *
 * @param id            系数版本 ID
 * @param instrumentModel 仪器型号
 * @param versionNo     型号内版本号
 * @param tempCoeff     温度线性补偿系数（十进制字符串）
 * @param humidityCoeff 湿度线性补偿系数（十进制字符串）
 * @param tempMin       适用温度下限（摄氏度，含端点，十进制字符串）
 * @param tempMax       适用温度上限（摄氏度，含端点，十进制字符串）
 * @param humidityMin   适用湿度下限（%RH，含端点，十进制字符串）
 * @param humidityMax   适用湿度上限（%RH，含端点，十进制字符串）
 * @param active        是否当前生效
 * @param createdAt     版本创建时间（UTC）
 */
public record CompensationProfileResponse(
        long id,
        String instrumentModel,
        int versionNo,
        String tempCoeff,
        String humidityCoeff,
        String tempMin,
        String tempMax,
        String humidityMin,
        String humidityMax,
        boolean active,
        Instant createdAt) {
}
