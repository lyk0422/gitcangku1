package com.example.starter.domain;

/**
 * 二次贝塞尔曲线段，三个点均为整数坐标（单位米）。
 * 曲线位置：B(t) = (1-t)^2*P0 + 2t(1-t)*P1 + t^2*P2，t∈[0,1]。
 * 控制点 P1 可在端点连线上（含取中点），此时曲线退化为直线，仍按曲线参数式审核。
 *
 * @param p0 固定起点（t=0）
 * @param p1 控制点（一般不经过曲线，决定切线与弯曲方向）
 * @param p2 固定终点（t=1）；多段航线中须等于下一段的 P0
 */
public record CurveSegment(Point p0, Point p1, Point p2) {
}
