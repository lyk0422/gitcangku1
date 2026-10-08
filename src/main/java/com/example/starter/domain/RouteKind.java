package com.example.starter.domain;

/**
 * 航线几何类型。
 *
 * <p>LINE：原直线类型，有序航点首尾直线连接，接口保持兼容；
 * CURVE：二次贝塞尔曲线段航线，每段固定 P0/P1/P2，最多 5 段且首尾相接。</p>
 */
public enum RouteKind {
    /** 直线段折线（航点顺序直线连接）。 */
    LINE,
    /** 二次贝塞尔曲线段航线。 */
    CURVE
}
