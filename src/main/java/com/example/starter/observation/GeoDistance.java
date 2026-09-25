package com.example.starter.observation;

import java.time.Duration;
import java.time.Instant;

/**
 * 冲突簇判定的地理与时间阈值工具：统一坐标球面距离不超过 50 米且采集时刻差不超过 60 秒，
 * 边界精确包含（恰好 50 米、恰好 60 秒均视为同簇）。
 */
public final class GeoDistance {

    /**
     * 地球平均半径（米），用于 haversine 球面距离计算。
     */
    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * 簇判定距离阈值（米）：距离小于等于该值视为同簇。
     */
    public static final double CLUSTER_DISTANCE_LIMIT_METERS = 50.0;

    /**
     * 簇判定采集时刻差阈值（秒）：时刻差绝对值小于等于该值视为同簇。
     */
    public static final long CLUSTER_TIME_LIMIT_SECONDS = 60L;

    private GeoDistance() {
    }

    /**
     * haversine 球面距离（米）：输入为十进制度坐标。
     */
    public static double haversineMeters(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double sinHalfLat = Math.sin(dLat / 2);
        double sinHalfLon = Math.sin(dLon / 2);
        double a = sinHalfLat * sinHalfLat
                + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * 距离维度同簇判定：球面距离小于等于 50 米（边界包含）。
     */
    public static boolean withinDistanceLimit(double lat1Deg, double lon1Deg, double lat2Deg, double lon2Deg) {
        return haversineMeters(lat1Deg, lon1Deg, lat2Deg, lon2Deg) <= CLUSTER_DISTANCE_LIMIT_METERS;
    }

    /**
     * 时间维度同簇判定：采集时刻差绝对值小于等于 60 秒（边界包含）。
     */
    public static boolean withinTimeLimit(Instant first, Instant second) {
        long seconds = Math.abs(Duration.between(first, second).getSeconds());
        return seconds <= CLUSTER_TIME_LIMIT_SECONDS;
    }

    /**
     * 完整同簇判定：距离与时刻差同时满足阈值（边界均包含）。
     */
    public static boolean sameCluster(double lat1Deg, double lon1Deg, Instant time1,
                                      double lat2Deg, double lon2Deg, Instant time2) {
        return withinTimeLimit(time1, time2)
                && withinDistanceLimit(lat1Deg, lon1Deg, lat2Deg, lon2Deg);
    }
}
