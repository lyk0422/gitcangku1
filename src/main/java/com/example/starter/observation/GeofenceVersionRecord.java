package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 围栏配置版本：对应 geofence_version 表的一行（关联 geofence_fence 取站点标识）。
 * 版本不可变：创建后任何字段都不再更新。
 *
 * @param fenceId          围栏唯一标识
 * @param siteId           所属站点标识
 * @param version          配置版本号，从 1 开始
 * @param minLat           矩形纬度下界（度，WGS84，含边界）
 * @param maxLat           矩形纬度上界（度，WGS84，含边界）
 * @param minLon           矩形经度下界（度，WGS84，含边界）
 * @param maxLon           矩形经度上界（度，WGS84，含边界）
 * @param effectiveFromUtc 生效区间起点（UTC，左闭）
 * @param effectiveToUtc   生效区间终点（UTC，右开）
 * @param operator         该版本配置的操作者标识
 * @param createdAtUtc     该版本创建时刻（UTC）
 */
public record GeofenceVersionRecord(
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
}
