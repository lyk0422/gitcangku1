package com.example.starter.api.dto;

/**
 * 单维度时效诊断：审核固化值与当前值对比。
 *
 * @param dimension    维度名：ROUTE / AIRSPACE / WEATHER_MINIMA / FORECAST / DUTY
 * @param frozenValue  审核时固化的版本值；null 表示审核未涉及该维度
 * @param currentValue 当前版本值
 * @param stale        true 表示该维度已偏离固化值
 */
public record VersionDiagnosticDto(
        String dimension,
        Long frozenValue,
        Long currentValue,
        Boolean stale) {
}
