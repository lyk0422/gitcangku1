package com.example.starter.api.dto;

import java.util.List;

/**
 * 预测气象版本明细查询视图。
 *
 * @param forecastVersion 预测版本
 * @param entries         该版本的全部预测条目（按 zoneId、startMinute 升序，稳定排序）
 */
public record ForecastView(Long forecastVersion, List<ForecastEntryDto> entries) {
}
