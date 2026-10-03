package com.example.starter.api.dto;

import java.util.List;

/**
 * 指定版本预测明细查询结果。条目按 zoneId、startUtc 升序，排序稳定。
 *
 * @param forecastVersion 预测版本（全局气象版本）
 * @param entries         截至该版本可见的预测条目（每区域每时段取最新版本）
 */
public record WeatherForecastDetailDto(long forecastVersion, List<WeatherForecastEntryDto> entries) {
}