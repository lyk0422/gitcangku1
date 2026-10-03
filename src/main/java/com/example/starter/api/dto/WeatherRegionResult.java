package com.example.starter.api.dto;

/**
 * 气象区域配置结果。
 *
 * @param regionId    气象区域标识
 * @param dutyVersion 本次配置生效后的气象值班版本
 * @param windowCount 本次配置的窗口数量
 */
public record WeatherRegionResult(String regionId, long dutyVersion, int windowCount) {
}