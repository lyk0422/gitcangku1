package com.example.starter.repo;

/**
 * 区域分时段气象最低标准记录。时段为 UTC 左闭右开区间。
 *
 * @param zoneId         所属区域标识
 * @param windowStartUtc 时段起点（含），epoch 毫秒（UTC）
 * @param windowEndUtc   时段终点（不含），epoch 毫秒（UTC）
 * @param minVisibilityM 最低能见度标准（米，>= 0）
 * @param minCloudBaseM  最低云底高度标准（米，>= 0）
 * @param weatherVersion 本次配置生效时的全局气象版本
 */
public record WeatherMinimaPo(String zoneId, long windowStartUtc, long windowEndUtc,
                              int minVisibilityM, int minCloudBaseM, long weatherVersion) {
}