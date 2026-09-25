package com.example.starter.calibration.api.dto;

/**
 * 发布（更新）仪器型号环境补偿系数版本请求。系数为线性公式：
 * compensation = k0 + kTemperature×温度(℃) + kHumidity×湿度(%RH)。
 *
 * @param instrumentModel 仪器型号
 * @param k0            补偿常数项（最多 6 位小数字符串）
 * @param kTemperature  温度线性系数（最多 6 位小数）
 * @param kHumidity    湿度线性系数（最多 6 位小数）
 * @param tempMin       适用温度下限（含端点，摄氏度，最多 4 位小数）
 * @param tempMax       适用温度上限（含端点，摄氏度，最多 4 位小数）
 * @param humidityMin    适用湿度下限（含端点，%RH，最多 4 位小数）
 * @param humidityMax    适用湿度上限（含端点，%RH，最多 4 位小数）
 * @param calcKey       幂等键；同键同指纹重放首次成功结果，失败不占键；可空
 */
public record PublishCoefficientRequest(
        String instrumentModel,
        String k0,
        String kTemperature,
        String kHumidity,
        String tempMin,
        String tempMax,
        String humidityMin,
        String humidityMax,
        String calcKey) {
}
