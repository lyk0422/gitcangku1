package com.example.starter.api.dto;

import java.util.List;

/**
 * 区域气象最低标准明细查询结果。窗口按 startUtc 升序，排序稳定。
 *
 * @param zoneId  区域标识
 * @param windows 当前生效的标准时段集合（按起点升序）
 */
public record WeatherMinimaDetailDto(String zoneId, List<WeatherWindowDto> windows) {
}