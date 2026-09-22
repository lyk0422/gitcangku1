package com.example.starter.api.dto;

/**
 * 航线创建/替换响应：携带变更后的航线版本。
 */
public record RouteResponse(String routeId, int version) {
}
