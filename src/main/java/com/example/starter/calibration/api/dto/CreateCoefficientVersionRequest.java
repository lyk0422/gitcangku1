package com.example.starter.calibration.api.dto;

/**
 * 写入（更新）环境补偿系数版本请求。版本只增不改：同一仪器已有开放版本时，
 * 开放版本会被闭合到本次生效起点，并追加新版本。
 *
 * @param instrumentId     仪器 ID
 * @param effectiveFrom    生效起点（UTC，含），ISO-8601；为 null 时取当前时刻
 * @param refTemperature   参考温度（℃）
 * @param refHumidity      参考湿度（%RH）
 * @param temperatureCoeff 温度系数（补偿量/℃）
 * @param humidityCoeff    湿度系数（补偿量/%RH）
 */
public record CreateCoefficientVersionRequest(
        String instrumentId,
        String effectiveFrom,
        String refTemperature,
        String refHumidity,
        String temperatureCoeff,
        String humidityCoeff) {
}
