package com.example.starter.domain;

/**
 * 二次贝塞尔曲线段，三个控制点均为二维整数坐标，单位米，范围 [-100000,100000]。
 *
 * <p>曲线上 t∈[0,1] 的位置为：
 * B(t)=(1-t)^2·P0 + 2t(1-t)·P1 + t^2·P2。
 * P0 为固定起点、P2 为固定终点；P1 为不在曲线上的控制点。
 * 三点共线或 P1 与端点重合时曲线退化为直线段，仍按同一二次式计算。</p>
 *
 * @param p0 固定起点（米）
 * @param p1 控制点（米）
 * @param p2 固定终点（米）
 */
public record QuadraticCurve(Point p0, Point p1, Point p2) {
}
