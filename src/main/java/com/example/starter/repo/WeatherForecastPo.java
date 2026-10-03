package com.example.starter.repo;

/**
 * 分版本预测气象记录（上传后不可变）。
 *
 * @param forecastVersion 预测上传生效的全局气象版本
 * @param zoneId          预测所属区域标识
 * @param windowStartUtc  预测时段起点（含），epoch 毫秒（UTC）
 * @param windowEndUtc    预测时段终点（不含），epoch 毫秒（UTC）
 * @param visibilityM     预测能见度（米，>= 0）
 * @param cloudBaseM      预测云底高度（米，>= 0）
 */
public record WeatherForecastPo(long forecastVersion, String zoneId,
                                long windowStartUtc, long windowEndUtc,
                                int visibilityM, int cloudBaseM) {
}