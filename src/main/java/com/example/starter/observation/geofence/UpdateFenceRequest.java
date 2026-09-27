package com.example.starter.observation.geofence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 变更围栏请求：为既有围栏追加一个不可变新版本（站点不可变）。
 * 校验规则与创建一致；新区间与同站点任一历史生效版本重叠返回 409。
 *
 * @param requestId        全局唯一请求标识（幂等去重键）
 * @param minLatitude      南边界纬度（含边界），[-90, 90]，最多 7 位小数
 * @param maxLatitude      北边界纬度（含边界），[-90, 90]，最多 7 位小数
 * @param minLongitude     西边界经度（含边界），[-180, 180]，最多 7 位小数
 * @param maxLongitude     东边界经度（含边界），[-180, 180]，最多 7 位小数
 * @param effectiveFromUtc 生效起始时刻（UTC，ISO-8601，左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，ISO-8601，右开），必须晚于起始时刻
 * @param operator         操作者标识
 */
public record UpdateFenceRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull BigDecimal minLatitude,
        @NotNull BigDecimal maxLatitude,
        @NotNull BigDecimal minLongitude,
        @NotNull BigDecimal maxLongitude,
        @NotBlank @Size(max = 64) String effectiveFromUtc,
        @NotBlank @Size(max = 64) String effectiveToUtc,
        @NotBlank @Size(max = 128) String operator) {
}
