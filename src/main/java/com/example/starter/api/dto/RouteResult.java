package com.example.starter.api.dto;

/**
 * 航线创建/替换结果。
 *
 * @param routeId 航线标识
 * @param version 操作后的航线版本
 */
public record RouteResult(String routeId, int version) {
}
