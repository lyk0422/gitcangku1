package com.example.starter.observation.geofence;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 围栏版本响应：字段与不可变围栏版本记录一一对应。
 *
 * @param fenceId          围栏唯一标识
 * @param fenceVersion     围栏版本号
 * @param siteId           站点唯一标识
 * @param minLatitude      南边界纬度（含边界）
 * @param maxLatitude      北边界纬度（含边界）
 * @param minLongitude     西边界经度（含边界）
 * @param maxLongitude     东边界经度（含边界）
 * @param effectiveFromUtc 生效起始时刻（UTC，ISO-8601）
 * @param effectiveToUtc   生效结束时刻（UTC，ISO-8601）
 * @param operator         配置该版本的操作者标识
 */
public record FenceResponse(
        String fenceId,
        int fenceVersion,
        String siteId,
        BigDecimal minLatitude,
        BigDecimal maxLatitude,
        BigDecimal minLongitude,
        BigDecimal maxLongitude,
        Instant effectiveFromUtc,
        Instant effectiveToUtc,
        String operator) {

    /**
     * 由围栏版本记录构造响应。
     */
    public static FenceResponse of(FenceVersionRecord record) {
        return new FenceResponse(record.fenceId(), record.fenceVersion(), record.siteId(),
                record.minLatitude(), record.maxLatitude(), record.minLongitude(), record.maxLongitude(),
                record.effectiveFromUtc(), record.effectiveToUtc(), record.operator());
    }
}
