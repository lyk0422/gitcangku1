package com.example.starter.observation.geofence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 创建站点矩形地理围栏请求：初始版本为 1。经纬度范围、边界或生效区间非法返回 422；
 * 同站点生效区间重叠返回 409。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param fenceId          围栏唯一标识
 * @param siteId           站点唯一标识
 * @param minLatitude      南边界纬度（含边界），[-90, 90]，最多 7 位小数
 * @param maxLatitude      北边界纬度（含边界），[-90, 90]，最多 7 位小数
 * @param minLongitude     西边界经度（含边界），[-180, 180]，最多 7 位小数
 * @param maxLongitude     东边界经度（含边界），[-180, 180]，最多 7 位小数
 * @param effectiveFromUtc 生效起始时刻（UTC，ISO-8601，左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，ISO-8601，右开），必须晚于起始时刻
 * @param operator         操作者标识
 */
public record CreateFenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String fenceId,
        @NotBlank @Size(max = 64) String siteId,
        @NotNull BigDecimal minLatitude,
        @NotNull BigDecimal maxLatitude,
        @NotNull BigDecimal minLongitude,
        @NotNull BigDecimal maxLongitude,
        @NotBlank @Size(max = 64) String effectiveFromUtc,
        @NotBlank @Size(max = 64) String effectiveToUtc,
        @NotBlank @Size(max = 128) String operator) {
}
