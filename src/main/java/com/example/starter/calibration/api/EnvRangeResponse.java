package com.example.starter.calibration.api;

import java.math.BigDecimal;

/**
 * 环境超出适用区间的 422 响应体：携带系数适用区间（含端点）。
 *
 * @param code       业务错误码 ENV_OUT_OF_COMPENSATION_RANGE
 * @param message    错误描述
 * @param instrumentModel 仪器型号
 * @param tempMin    适用温度下限（摄氏度，含端点）
 * @param tempMax    适用温度上限（摄氏度，含端点）
 * @param humidityMin 适用湿度下限（%RH，含端点）
 * @param humidityMax 适用湿度上限（%RH，含端点）
 */
public record EnvRangeResponse(
        String code,
        String message,
        String instrumentModel,
        BigDecimal tempMin,
        BigDecimal tempMax,
        BigDecimal humidityMin,
        BigDecimal humidityMax) {
}
