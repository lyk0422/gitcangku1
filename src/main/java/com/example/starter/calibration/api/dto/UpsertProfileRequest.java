package com.example.starter.calibration.api.dto;

/**
 * 创建/更新环境补偿系数版本请求。按仪器型号配置线性补偿系数与温湿度适用区间。
 * 更新（型号已有版本）只影响后续测量：追加并激活新版本，旧版本快照不改写。
 *
 * @param instrumentModel 仪器型号
 * @param tempCoeff       温度线性补偿系数（补偿量/摄氏度）
 * @param humidityCoeff   湿度线性补偿系数（补偿量/%RH）
 * @param tempMin         适用温度下限（摄氏度，含端点）
 * @param tempMax         适用温度上限（摄氏度，含端点）
 * @param humidityMin     适用湿度下限（%RH，含端点）
 * @param humidityMax     适用湿度上限（%RH，含端点）
 */
public record UpsertProfileRequest(
        String instrumentModel,
        String tempCoeff,
        String humidityCoeff,
        String tempMin,
        String tempMax,
        String humidityMin,
        String humidityMax) {
}
