package com.example.starter.api.dto;

/**
 * 航线停用结果。
 *
 * @param routeId 航线标识
 * @param removed 移除的时空桶占用条数
 */
public record RouteDeactivateResult(String routeId, int removed) {
}
