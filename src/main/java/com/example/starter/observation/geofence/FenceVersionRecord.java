package com.example.starter.observation.geofence;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 围栏版本记录：对应 site_geofence 表的一行，落库后不可变。
 *
 * @param fenceId          围栏唯一标识
 * @param fenceVersion     围栏版本号，从 1 开始
 * @param siteId           站点唯一标识
 * @param minLatitude      南边界纬度（含边界）
 * @param maxLatitude      北边界纬度（含边界）
 * @param minLongitude     西边界经度（含边界）
 * @param maxLongitude     东边界经度（含边界）
 * @param effectiveFromUtc 生效起始时刻（UTC，左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，右开）
 * @param operator         配置该版本的操作者标识
 * @param requestId        生成该版本的请求标识
 */
public record FenceVersionRecord(
        String fenceId,
        int fenceVersion,
        String siteId,
        BigDecimal minLatitude,
        BigDecimal maxLatitude,
        BigDecimal minLongitude,
        BigDecimal maxLongitude,
        Instant effectiveFromUtc,
        Instant effectiveToUtc,
        String operator,
        String requestId) {
}
