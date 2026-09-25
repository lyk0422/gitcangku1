package com.example.starter.observation;

/**
 * 坐标基准版本响应。
 *
 * @param frameVersion 坐标基准版本标识
 * @param offsetLatDeg 纬度固定偏移量（度）
 * @param offsetLonDeg 经度固定偏移量（度）
 */
public record FrameResponse(
        String frameVersion,
        double offsetLatDeg,
        double offsetLonDeg) {

    public static FrameResponse of(CoordinateFrame frame) {
        return new FrameResponse(frame.frameVersion(), frame.offsetLatDeg(), frame.offsetLonDeg());
    }
}
