package com.example.starter.api.dto;

import java.util.List;

/**
 * 区域气象最低标准配置结果/明细查询视图。
 *
 * @param zoneId         区域标识
 * @param weatherVersion 该组标准生效时的气象标准版本
 * @param windows        标准时段（按 windowStartMinute、windowEndMinute 升序，稳定排序）
 */
public record WeatherMinimaResult(
        String zoneId,
        Long weatherVersion,
        List<WeatherWindowDto> windows) {
}
