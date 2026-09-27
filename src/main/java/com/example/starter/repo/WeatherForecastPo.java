package com.example.starter.repo;

/**
 * 预测气象持久化记录（按版本不可变，时段左闭右开）。
 *
 * @param forecastVersion 所属预测气象版本
 * @param zoneId          预测区域标识
 * @param validFrom       预测时段起点（含），epoch 毫秒（UTC）
 * @param validTo         预测时段终点（不含），epoch 毫秒（UTC）
 * @param visibilityM     预测能见度，米，>= 0
 * @param cloudBaseM      预测云底，米，>= 0
 */
public record WeatherForecastPo(long forecastVersion, String zoneId, long validFrom, long validTo,
                                int visibilityM, int cloudBaseM) {
}
