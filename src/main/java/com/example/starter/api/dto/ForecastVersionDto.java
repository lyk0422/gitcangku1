package com.example.starter.api.dto;

import java.util.List;

/**
 * 预测气象版本明细查询结果。
 *
 * @param forecastVersion 预测气象版本
 * @param entryCount      条目数量
 * @param entries         预测条目（按 zoneId、时段起点升序，稳定排序）
 */
public record ForecastVersionDto(
        Long forecastVersion,
        Integer entryCount,
        List<ForecastEntryDto> entries) {
}
