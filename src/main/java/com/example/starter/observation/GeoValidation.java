package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 地理围栏与采集坐标的语义校验：经纬度范围、矩形边界方向与生效区间（UTC，左闭右开）。
 * 校验失败一律 422。围栏配置与离线提交共用同一套规则。
 */
final class GeoValidation {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    private GeoValidation() {
    }

    /**
     * 校验单个坐标点：纬度 [-90,90]，经度 [-180,180]，否则 422。
     */
    static void validatePoint(BigDecimal latitude, BigDecimal longitude) {
        checkRange("latitude", latitude, MIN_LATITUDE, MAX_LATITUDE);
        checkRange("longitude", longitude, MIN_LONGITUDE, MAX_LONGITUDE);
    }

    /**
     * 校验矩形围栏边界：四边均在合法经纬度范围内，且下界严格小于上界，否则 422。
     */
    static void validateFenceBounds(BigDecimal minLat, BigDecimal maxLat,
                                    BigDecimal minLon, BigDecimal maxLon) {
        checkRange("minLat", minLat, MIN_LATITUDE, MAX_LATITUDE);
        checkRange("maxLat", maxLat, MIN_LATITUDE, MAX_LATITUDE);
        checkRange("minLon", minLon, MIN_LONGITUDE, MAX_LONGITUDE);
        checkRange("maxLon", maxLon, MIN_LONGITUDE, MAX_LONGITUDE);
        if (minLat.compareTo(maxLat) >= 0) {
            throw ApiException.unprocessable(
                    "minLat must be less than maxLat: " + minLat + " >= " + maxLat);
        }
        if (minLon.compareTo(maxLon) >= 0) {
            throw ApiException.unprocessable(
                    "minLon must be less than maxLon: " + minLon + " >= " + maxLon);
        }
    }

    /**
     * 校验生效区间：起点必须严格早于终点（左闭右开区间），否则 422。
     */
    static void validateEffectiveInterval(Instant effectiveFromUtc, Instant effectiveToUtc) {
        if (!effectiveFromUtc.isBefore(effectiveToUtc)) {
            throw ApiException.unprocessable(
                    "effectiveFromUtc must be before effectiveToUtc: " + effectiveFromUtc
                            + " !< " + effectiveToUtc);
        }
    }

    private static void checkRange(String field, BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw ApiException.unprocessable(
                    field + " out of range: " + value + " not in [" + min + ", " + max + "]");
        }
    }
}
