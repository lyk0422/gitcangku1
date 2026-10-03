package com.example.starter.api.dto;

/**
 * 气象最低标准配置结果。
 *
 * @param zoneId         区域标识
 * @param weatherVersion 配置生效后的全局气象（值班）版本
 * @param windowCount    生效的标准时段数量（实际值）
 */
public record WeatherMinimaResult(String zoneId, long weatherVersion, int windowCount) {
}