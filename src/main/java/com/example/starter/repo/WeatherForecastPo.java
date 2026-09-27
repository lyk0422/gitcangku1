package com.example.starter.repo;

/**
 * 预测气象持久化记录（不可变，按上传批次版本化）。
 *
 * @param forecastVersion 所属全局预测版本
 * @param zoneId          预测覆盖的区域标识
 * @param windowStart     时段起始（UTC 一日内分钟，含）
 * @param windowEnd       时段结束（UTC 一日内分钟，不含）
 * @param visibilityM     预测能见度，米
 * @param cloudBaseM      预测云底高，米
 * @param createdAt       上传时间，epoch 毫秒（UTC）
 */
public record WeatherForecastPo(long forecastVersion, String zoneId, int windowStart, int windowEnd,
                                int visibilityM, int cloudBaseM, long createdAt) {
}
