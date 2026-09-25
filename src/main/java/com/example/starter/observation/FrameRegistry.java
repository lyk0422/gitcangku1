package com.example.starter.observation;

import java.util.Map;
import java.util.Optional;

/**
 * 坐标基准注册表：公开的固定偏移参数。统一基准坐标 = 原始坐标 + 偏移量（纬度、经度，单位为度）。
 * 偏移常量均取 2 的负幂，保证双精度加法无舍入误差，转换结果可精确复算。
 * 未知基准版本由业务层判定为 422。
 */
public final class FrameRegistry {

    /**
     * 单个基准版本的固定偏移参数。
     *
     * @param frameVersion    基准版本标识
     * @param latitudeOffset  纬度偏移量（度），加在原始纬度上得到统一纬度
     * @param longitudeOffset 经度偏移量（度），加在原始经度上得到统一经度
     */
    public record FrameOffset(String frameVersion, double latitudeOffset, double longitudeOffset) {

        /**
         * 将原始纬度转换为统一基准纬度（度）。
         */
        public double unifiedLatitude(double rawLatitude) {
            return rawLatitude + latitudeOffset;
        }

        /**
         * 将原始经度转换为统一基准经度（度）。
         */
        public double unifiedLongitude(double rawLongitude) {
            return rawLongitude + longitudeOffset;
        }
    }

    private static final Map<String, FrameOffset> OFFSETS = Map.of(
            "WGS84", new FrameOffset("WGS84", 0.0, 0.0),
            "GCJ02", new FrameOffset("GCJ02", -0.0078125, -0.00390625),
            "BD09", new FrameOffset("BD09", -0.015625, -0.01171875),
            "CGCS2000", new FrameOffset("CGCS2000", 0.00390625, 0.00390625));

    private FrameRegistry() {
    }

    /**
     * 按版本标识查找基准偏移参数；未知版本返回空。
     */
    public static Optional<FrameOffset> find(String frameVersion) {
        return Optional.ofNullable(OFFSETS.get(frameVersion));
    }

    /**
     * 经纬度范围校验：纬度 [-90, 90]、经度 [-180, 180]，边界包含；非有限数值一律非法。
     */
    public static boolean inRange(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90.0 && latitude <= 90.0
                && longitude >= -180.0 && longitude <= 180.0;
    }
}
