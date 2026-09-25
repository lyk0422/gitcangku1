package com.example.starter.observation;

/**
 * 坐标基准版本：公开的固定偏移参数，登记后不可改写。
 *
 * @param frameVersion 坐标基准版本标识
 * @param offsetLatDeg 纬度固定偏移量（度，正数向北）
 * @param offsetLonDeg 经度固定偏移量（度，正数向东）
 */
public record CoordinateFrame(
        String frameVersion,
        double offsetLatDeg,
        double offsetLonDeg) {

    /**
     * 将原始坐标按固定偏移参数换算为统一基准坐标。
     */
    public double[] toUnified(double rawLatitude, double rawLongitude) {
        return new double[]{rawLatitude + offsetLatDeg, rawLongitude + offsetLonDeg};
    }
}
