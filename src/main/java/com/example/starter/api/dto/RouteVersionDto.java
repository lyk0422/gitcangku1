package com.example.starter.api.dto;

/**
 * 航线当前版本信息（预览返回）。
 *
 * @param routeId        航线标识
 * @param currentVersion 当前航线版本；航线不存在时为 null
 */
public record RouteVersionDto(String routeId, Integer currentVersion) {
}
