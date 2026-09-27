package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 变更站点矩形地理围栏请求：expectedVersion 匹配当前围栏版本时生成新版本；
 * 历史版本保留，不重算历史提交的匹配结果。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param expectedVersion  期望变更时的当前围栏版本号
 * @param minLatitude      矩形围栏最小纬度（含边界），范围 [-90,90]
 * @param maxLatitude      矩形围栏最大纬度（含边界），范围 [-90,90]
 * @param minLongitude     矩形围栏最小经度（含边界），范围 [-180,180]
 * @param maxLongitude     矩形围栏最大经度（含边界），范围 [-180,180]
 * @param effectiveFromUtc 生效起始时刻（UTC，区间左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，区间右开）
 * @param operator         操作者标识
 */
public record UpdateGeofenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull BigDecimal minLatitude,
        @NotNull BigDecimal maxLatitude,
        @NotNull BigDecimal minLongitude,
        @NotNull BigDecimal maxLongitude,
        @NotNull Instant effectiveFromUtc,
        @NotNull Instant effectiveToUtc,
        @NotBlank @Size(max = 128) String operator) {
}
