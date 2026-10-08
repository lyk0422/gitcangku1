package com.example.starter.domain;

/**
 * 航线几何类型。
 */
public enum RouteKind {
    /** 折线：有序航点直线连接（原有航线类型，接口保持兼容）。 */
    LINE,
    /** 二次贝塞尔曲线：1~5 段，每段由起点 P0、控制点 P1、终点 P2 定义。 */
    QUADRATIC
}
