package com.example.starter.calibration.api;

/**
 * 环境超区间 422 错误响应体：除错误码与描述外，携带补偿系数适用的温湿度区间。
 *
 * @param code    业务错误码
 * @param message 错误描述
 * @param range   适用温湿度区间（含端点）
 */
public record EnvironmentErrorResponse(String code, String message, EnvironmentOutOfRangeException.Range range) {
}
