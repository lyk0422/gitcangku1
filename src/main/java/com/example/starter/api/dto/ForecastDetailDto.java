package com.example.starter.api.dto;

import java.util.List;

/**
 * 预测气象版本明细查询结果（只读）。
 *
 * @param forecastVersion 预测气象版本
 * @param latest          是否为当前最新预测版本
 * @param entries         该版本全部条目（按 regionId、时段起点升序）
 */
public record ForecastDetailDto(long forecastVersion, boolean latest, List<ForecastEntryDto> entries) {
}