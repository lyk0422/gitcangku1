package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建站点矩形地理围栏请求：初始围栏版本为 1。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param fenceId          围栏唯一标识
 * @param siteId           站点标识
 * @param minLatitude      矩形围栏最小纬度（含边界），范围 [-90,90]
 * @param maxLatitude      矩形围栏最大纬度（含边界），范围 [-90,90]
 * @param minLongitude     矩形围栏最小经度（含边界），范围 [-180,180]
 * @param maxLongitude     矩形围栏最大经度（含边界），范围 [-180,180]
 * @param effectiveFromUtc 生效起始时刻（UTC，区间左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，区间右开）
 * @param operator         操作者标识
 */
public record CreateGeofenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String fenceId,
        @NotBlank @Size(max = 64) String siteId,
        @NotNull BigDecimal minLatitude,
        @NotNull BigDecimal maxLatitude,
        @NotNull BigDecimal minLongitude,
        @NotNull BigDecimal maxLongitude,
        @NotNull Instant effectiveFromUtc,
        @NotNull Instant effectiveToUtc,
        @NotBlank @Size(max = 128) String operator) {
}
