package com.example.starter.calibration.model;

import java.math.BigDecimal;

/**
 * 记录的测量环境（温度、湿度）。两者必须同时提供；用于按型号系数版本计算环境补偿。
 *
 * @param instrumentModel 仪器型号
 * @param temperatureC    环境温度（摄氏度）
 * @param humidityPct   环境相对湿度（%RH）
 */
public record EnvRecord(
        String instrumentModel,
        BigDecimal temperatureC,
        BigDecimal humidityPct) {
}
