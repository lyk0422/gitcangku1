package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 地理坐标与围栏配置的语义校验：经纬度范围、矩形边界与生效区间非法时抛出 422，
 * 错误中携带实际值与要求值（允许范围）。
 */
final class GeoValidation {

    private static final BigDecimal MIN_LATITUDE = new BigDecimal("-90");
    private static final BigDecimal MAX_LATITUDE = new BigDecimal("90");
    private static final BigDecimal MIN_LONGITUDE = new BigDecimal("-180");
    private static final BigDecimal MAX_LONGITUDE = new BigDecimal("180");

    private static final String LATITUDE_RANGE = "[-90, 90]";
    private static final String LONGITUDE_RANGE = "[-180, 180]";

    private GeoValidation() {
    }

    /**
     * 校验单个纬度值在 [-90,90] 内；否则 422。
     */
    static void requireLatitude(String field, BigDecimal value) {
        if (value.compareTo(MIN_LATITUDE) < 0 || value.compareTo(MAX_LATITUDE) > 0) {
            throw ApiException.unprocessable(
                    field + " out of range: " + value.toPlainString() + ", required " + LATITUDE_RANGE,
                    value.toPlainString(), LATITUDE_RANGE);
        }
    }

    /**
     * 校验单个经度值在 [-180,180] 内；否则 422。
     */
    static void requireLongitude(String field, BigDecimal value) {
        if (value.compareTo(MIN_LONGITUDE) < 0 || value.compareTo(MAX_LONGITUDE) > 0) {
            throw ApiException.unprocessable(
                    field + " out of range: " + value.toPlainString() + ", required " + LONGITUDE_RANGE,
                    value.toPlainString(), LONGITUDE_RANGE);
        }
    }

    /**
     * 校验围栏矩形边界与生效区间：纬度/经度各自在合法范围内、最小值严格小于最大值、
     * 生效区间起始严格早于结束（左闭右开）；任一不满足返回 422。
     */
    static void requireValidFence(BigDecimal minLatitude, BigDecimal maxLatitude,
                                  BigDecimal minLongitude, BigDecimal maxLongitude,
                                  Instant effectiveFromUtc, Instant effectiveToUtc) {
        requireLatitude("minLatitude", minLatitude);
        requireLatitude("maxLatitude", maxLatitude);
        requireLongitude("minLongitude", minLongitude);
        requireLongitude("maxLongitude", maxLongitude);
        if (minLatitude.compareTo(maxLatitude) >= 0) {
            throw ApiException.unprocessable(
                    "fence latitude boundary invalid: minLatitude " + minLatitude.toPlainString()
                            + " must be less than maxLatitude " + maxLatitude.toPlainString(),
                    minLatitude.toPlainString() + " >= " + maxLatitude.toPlainString(),
                    "minLatitude < maxLatitude");
        }
        if (minLongitude.compareTo(maxLongitude) >= 0) {
            throw ApiException.unprocessable(
                    "fence longitude boundary invalid: minLongitude " + minLongitude.toPlainString()
                            + " must be less than maxLongitude " + maxLongitude.toPlainString(),
                    minLongitude.toPlainString() + " >= " + maxLongitude.toPlainString(),
                    "minLongitude < maxLongitude");
        }
        if (!effectiveFromUtc.isBefore(effectiveToUtc)) {
            throw ApiException.unprocessable(
                    "fence effective interval invalid: effectiveFromUtc " + effectiveFromUtc
                            + " must be before effectiveToUtc " + effectiveToUtc + " (left-closed right-open)",
                    effectiveFromUtc + " .. " + effectiveToUtc,
                    "effectiveFromUtc < effectiveToUtc");
        }
    }
}
