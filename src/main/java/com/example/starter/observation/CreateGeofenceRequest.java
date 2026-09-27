package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建站点矩形地理围栏请求。经纬度范围、边界大小关系与生效区间合法性由服务端校验（422）。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param fenceId          全局唯一围栏标识
 * @param siteId           站点标识
 * @param operator         操作者标识
 * @param minLat           矩形南边界纬度，[-90,90]
 * @param maxLat           矩形北边界纬度，[-90,90]
 * @param minLon           矩形西边界经度，[-180,180]
 * @param maxLon           矩形东边界经度，[-180,180]
 * @param effectiveFromUtc 生效起点（UTC，闭区间），ISO-8601 如 2026-09-27T10:00:00Z
 * @param effectiveToUtc   生效终点（UTC，开区间），ISO-8601
 */
public record CreateGeofenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String fenceId,
        @NotBlank @Size(max = 64) String siteId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull BigDecimal minLat,
        @NotNull BigDecimal maxLat,
        @NotNull BigDecimal minLon,
        @NotNull BigDecimal maxLon,
        @NotNull Instant effectiveFromUtc,
        @NotNull Instant effectiveToUtc) {
}
