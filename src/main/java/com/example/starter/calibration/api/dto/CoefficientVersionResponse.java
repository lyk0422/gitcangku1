package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 系数版本响应。effectiveTo 为 null 表示当前仍开放。
 *
 * @param id               系数版本 ID
 * @param instrumentId     仪器 ID
 * @param effectiveFrom    生效起点（UTC，含）
 * @param effectiveTo      生效终点（UTC，不含）；null 表示开放至今
 * @param refTemperature   参考温度（℃）
 * @param refHumidity      参考湿度（%RH）
 * @param temperatureCoeff 温度系数（补偿量/℃）
 * @param humidityCoeff    湿度系数（补偿量/%RH）
 * @param createdAt        版本创建时间（UTC）
 */
public record CoefficientVersionResponse(
        long id,
        String instrumentId,
        Instant effectiveFrom,
        Instant effectiveTo,
        String refTemperature,
        String refHumidity,
        String temperatureCoeff,
        String humidityCoeff,
        Instant createdAt) {
}
