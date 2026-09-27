package com.example.starter.api.dto;

import java.util.List;

/**
 * 气象最低标准配置结果。
 *
 * @param zoneId           区域标识
 * @param standardsVersion 替换后生效的气象标准版本
 * @param windowCount      生效时段数量
 * @param windows          生效时段（按起点升序，稳定排序）
 */
public record WeatherMinimaResult(
        String zoneId,
        Long standardsVersion,
        Integer windowCount,
        List<WeatherMinimaWindowDto> windows) {
}
