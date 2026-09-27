package com.example.starter.repo;

/**
 * 区域气象最低标准持久化记录（按 UTC 时段生效，左闭右开）。
 *
 * @param zoneId           所属区域标识
 * @param effectiveFrom    时段起点（含），epoch 毫秒（UTC）
 * @param effectiveTo      时段终点（不含），epoch 毫秒（UTC）
 * @param minVisibilityM   能见度最低标准，米，>= 0
 * @param minCloudBaseM    云底最低标准，米，>= 0
 * @param standardsVersion 本行写入时生效的气象标准版本
 */
public record WeatherMinimaPo(String zoneId, long effectiveFrom, long effectiveTo,
                              int minVisibilityM, int minCloudBaseM, long standardsVersion) {
}
