package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 地理围栏响应：围栏配置创建后不可变，查询结果稳定。
 *
 * @param fenceId          全局唯一围栏标识
 * @param siteId           站点标识
 * @param minLat           矩形南边界纬度
 * @param maxLat           矩形北边界纬度
 * @param minLon           矩形西边界经度
 * @param maxLon           矩形东边界经度
 * @param effectiveFromUtc 生效起点（UTC，闭区间）
 * @param effectiveToUtc   生效终点（UTC，开区间）
 * @param operator         配置该围栏的操作者标识
 * @param createdAtUtc     围栏配置落库时刻（UTC）
 */
public record GeofenceResponse(
        String fenceId,
        String siteId,
        BigDecimal minLat,
        BigDecimal maxLat,
        BigDecimal minLon,
        BigDecimal maxLon,
        Instant effectiveFromUtc,
        Instant effectiveToUtc,
        String operator,
        Instant createdAtUtc) {

    /**
     * 由围栏记录构造响应。
     */
    public static GeofenceResponse of(Geofence geofence) {
        return new GeofenceResponse(geofence.fenceId(), geofence.siteId(),
                geofence.minLat(), geofence.maxLat(), geofence.minLon(), geofence.maxLon(),
                geofence.effectiveFromUtc(), geofence.effectiveToUtc(),
                geofence.operator(), geofence.createdAtUtc());
    }
}
