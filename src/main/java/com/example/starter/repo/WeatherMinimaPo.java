package com.example.starter.repo;

/**
 * 区域气象最低标准时段持久化记录。
 *
 * @param zoneId          区域标识
 * @param windowStart     时段起始（UTC 一日内分钟，含）
 * @param windowEnd       时段结束（UTC 一日内分钟，不含）
 * @param minVisibilityM  最低能见度标准，米
 * @param minCloudBaseM   最低云底高标准，米
 * @param minimaVersion   生效时的气象标准版本
 */
public record WeatherMinimaPo(String zoneId, int windowStart, int windowEnd,
                              int minVisibilityM, int minCloudBaseM, long minimaVersion) {
}
