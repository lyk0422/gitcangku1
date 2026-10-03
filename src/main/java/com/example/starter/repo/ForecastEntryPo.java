package com.example.starter.repo;

/**
 * 预测气象条目（按版本固化，不可变）。
 *
 * @param forecastVersion 预测气象版本
 * @param regionId        气象区域标识
 * @param windowStart     时段起点（epoch 毫秒，UTC，左闭）
 * @param windowEnd       时段终点（epoch 毫秒，UTC，右开）
 * @param visibilityM     预测能见度（米，非负）
 * @param cloudBaseM      预测云底（米，非负）
 */
public record ForecastEntryPo(long forecastVersion, String regionId, long windowStart, long windowEnd,
                              int visibilityM, int cloudBaseM) {
}