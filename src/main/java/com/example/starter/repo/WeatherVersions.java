package com.example.starter.repo;

/**
 * 气象版本元数据（单行）。
 *
 * @param weatherVersion  当前气象最低标准版本
 * @param forecastVersion 当前最新预测气象版本
 * @param dutyVersion     当前值班版本
 */
public record WeatherVersions(long weatherVersion, long forecastVersion, long dutyVersion) {
}
