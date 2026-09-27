package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建矩形地理围栏请求：初始配置版本为 1。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param operator         操作者标识
 * @param fenceId          围栏唯一标识
 * @param siteId           所属站点标识（创建后不可变）
 * @param minLat           矩形纬度下界（度，WGS84，含边界，[-90,90]）
 * @param maxLat           矩形纬度上界（度，WGS84，含边界，[-90,90]）
 * @param minLon           矩形经度下界（度，WGS84，含边界，[-180,180]）
 * @param maxLon           矩形经度上界（度，WGS84，含边界，[-180,180]）
 * @param effectiveFromUtc 生效区间起点（UTC，左闭）
 * @param effectiveToUtc   生效区间终点（UTC，右开）
 */
public record CreateGeofenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator,
        @NotBlank @Size(max = 64) String fenceId,
        @NotBlank @Size(max = 64) String siteId,
        @NotNull BigDecimal minLat,
        @NotNull BigDecimal maxLat,
        @NotNull BigDecimal minLon,
        @NotNull BigDecimal maxLon,
        @NotNull Instant effectiveFromUtc,
        @NotNull Instant effectiveToUtc) {
}
