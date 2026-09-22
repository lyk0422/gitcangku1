package com.example.starter.api.dto;

/**
 * 禁飞区创建/撤销响应：携带变更后的全局空域版本。
 */
public record ZoneResponse(
        String zoneId,
        int minX,
        int minY,
        int maxX,
        int maxY,
        boolean active,
        long airspaceVersion) {
}
