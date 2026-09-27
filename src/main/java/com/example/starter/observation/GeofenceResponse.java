package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 地理围栏响应：返回围栏某一版本的完整配置。
 *
 * @param fenceId          围栏唯一标识
 * @param fenceVersion     围栏版本号
 * @param siteId           站点标识
 * @param minLatitude      矩形围栏最小纬度（含边界）
 * @param maxLatitude      矩形围栏最大纬度（含边界）
 * @param minLongitude     矩形围栏最小经度（含边界）
 * @param maxLongitude     矩形围栏最大经度（含边界）
 * @param effectiveFromUtc 生效起始时刻（UTC，ISO-8601，区间左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，ISO-8601，区间右开）
 * @param operator         配置该版本的操作者标识
 */
public record GeofenceResponse(
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
    public static GeofenceResponse of(GeofenceRecord record) {
        return new GeofenceResponse(record.fenceId(), record.fenceVersion(), record.siteId(),
                record.minLatitude(), record.maxLatitude(), record.minLongitude(), record.maxLongitude(),
                record.effectiveFromUtc(), record.effectiveToUtc(), record.operator());
    }
}
