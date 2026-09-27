package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 围栏配置版本响应：创建、改版与查询共用的稳定结构。
 *
 * @param fenceId          围栏唯一标识
 * @param siteId           所属站点标识
 * @param version          配置版本号
 * @param minLat           矩形纬度下界（度，WGS84，含边界）
 * @param maxLat           矩形纬度上界（度，WGS84，含边界）
 * @param minLon           矩形经度下界（度，WGS84，含边界）
 * @param maxLon           矩形经度上界（度，WGS84，含边界）
 * @param effectiveFromUtc 生效区间起点（UTC，左闭）
 * @param effectiveToUtc   生效区间终点（UTC，右开）
 * @param operator         该版本配置的操作者标识
 * @param createdAtUtc     该版本创建时刻（UTC）
 */
public record GeofenceResponse(
        String fenceId,
        String siteId,
        int version,
        BigDecimal minLat,
        BigDecimal maxLat,
        BigDecimal minLon,
        BigDecimal maxLon,
        Instant effectiveFromUtc,
        Instant effectiveToUtc,
        String operator,
        Instant createdAtUtc) {

    /**
     * 由配置版本记录构造响应。
     */
    public static GeofenceResponse of(GeofenceVersionRecord record) {
        return new GeofenceResponse(record.fenceId(), record.siteId(), record.version(),
                record.minLat(), record.maxLat(), record.minLon(), record.maxLon(),
                record.effectiveFromUtc(), record.effectiveToUtc(),
                record.operator(), record.createdAtUtc());
    }
}
