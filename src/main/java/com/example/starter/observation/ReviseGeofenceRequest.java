package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 围栏改版请求：为既有围栏追加一个不可变配置版本（版本号加一），历史版本保留。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param operator         操作者标识
 * @param minLat           新矩形纬度下界（度，WGS84，含边界，[-90,90]）
 * @param maxLat           新矩形纬度上界（度，WGS84，含边界，[-90,90]）
 * @param minLon           新矩形经度下界（度，WGS84，含边界，[-180,180]）
 * @param maxLon           新矩形经度上界（度，WGS84，含边界，[-180,180]）
 * @param effectiveFromUtc 新生效区间起点（UTC，左闭）
 * @param effectiveToUtc   新生效区间终点（UTC，右开）
 */
public record ReviseGeofenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull BigDecimal minLat,
        @NotNull BigDecimal maxLat,
        @NotNull BigDecimal minLon,
        @NotNull BigDecimal maxLon,
        @NotNull Instant effectiveFromUtc,
        @NotNull Instant effectiveToUtc) {
}
