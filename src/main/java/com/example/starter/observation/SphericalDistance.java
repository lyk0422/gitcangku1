package com.example.starter.observation;

/**
 * 球面距离计算（haversine 公式），用于冲突簇的距离判定。
 */
public final class SphericalDistance {

    /**
     * 地球平均半径（米）。
     */
    public static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * 冲突簇距离阈值（米）：统一坐标球面距离小于等于该值才满足同簇距离条件，边界精确包含。
     */
    public static final double CLUSTER_RADIUS_METERS = 50.0;

    /**
     * 距离边界浮点容差（米）：吸收双精度计算的舍入误差（量级约 1e-10），
     * 保证恰好 50 米的点对稳定入簇；容差远小于 1 毫米，不影响业务判定。
     */
    public static final double BOUNDARY_TOLERANCE_METERS = 1e-6;

    private SphericalDistance() {
    }

    /**
     * 计算两个统一基准坐标点之间的球面距离（米）。
     */
    public static double meters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double sinDPhi = Math.sin(dPhi / 2);
        double sinDLambda = Math.sin(dLambda / 2);
        double a = sinDPhi * sinDPhi + Math.cos(phi1) * Math.cos(phi2) * sinDLambda * sinDLambda;
        return EARTH_RADIUS_METERS * 2 * Math.asin(Math.sqrt(a));
    }

    /**
     * 判断两点距离是否满足同簇距离条件（不超过 50 米，边界包含）。
     */
    public static boolean withinClusterRadius(double lat1, double lon1, double lat2, double lon2) {
        return meters(lat1, lon1, lat2, lon2) <= CLUSTER_RADIUS_METERS + BOUNDARY_TOLERANCE_METERS;
    }
}
