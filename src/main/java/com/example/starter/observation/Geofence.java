package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 站点矩形地理围栏：对应 geofence 表的一行，创建后不可变（无更新入口）。
 * 生效区间按 UTC 左闭右开 [effectiveFromUtc, effectiveToUtc) 解释；矩形边界含端点。
 *
 * @param fenceId          全局唯一围栏标识（围栏版本载体：配置不可变，改动只能新建围栏）
 * @param siteId           站点标识
 * @param minLat           矩形南边界纬度，[-90,90]
 * @param maxLat           矩形北边界纬度，[-90,90]，大于 minLat
 * @param minLon           矩形西边界经度，[-180,180]
 * @param maxLon           矩形东边界经度，[-180,180]，大于 minLon
 * @param effectiveFromUtc 生效起点（UTC，闭区间）
 * @param effectiveToUtc   生效终点（UTC，开区间），大于 effectiveFromUtc
 * @param operator         配置该围栏的操作者标识
 * @param createdAtUtc     围栏配置落库时刻（UTC）
 */
public record Geofence(
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
}
