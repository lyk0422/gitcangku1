package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 地理围栏版本记录：对应 geofence 表的一行（某一围栏的某一版本），不可变。
 *
 * @param fenceId          围栏唯一标识
 * @param fenceVersion     围栏版本号，从 1 开始单调递增
 * @param siteId           站点标识
 * @param minLatitude      矩形围栏最小纬度（含边界），范围 [-90,90]
 * @param maxLatitude      矩形围栏最大纬度（含边界），范围 [-90,90]
 * @param minLongitude     矩形围栏最小经度（含边界），范围 [-180,180]
 * @param maxLongitude     矩形围栏最大经度（含边界），范围 [-180,180]
 * @param effectiveFromUtc 生效起始时刻（UTC，区间左闭）
 * @param effectiveToUtc   生效结束时刻（UTC，区间右开）
 * @param operator         配置该版本的操作者标识
 * @param requestId        产生该版本的请求标识
 */
public record GeofenceRecord(
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

    /**
     * 判断采集时刻是否落在本版本生效区间内（UTC，左闭右开）。
     */
    public boolean covers(Instant collectedAtUtc) {
        return !collectedAtUtc.isBefore(effectiveFromUtc) && collectedAtUtc.isBefore(effectiveToUtc);
    }

    /**
     * 判断坐标是否落在矩形围栏内（边界含端点）。
     */
    public boolean contains(BigDecimal latitude, BigDecimal longitude) {
        return latitude.compareTo(minLatitude) >= 0 && latitude.compareTo(maxLatitude) <= 0
                && longitude.compareTo(minLongitude) >= 0 && longitude.compareTo(maxLongitude) <= 0;
    }
}
