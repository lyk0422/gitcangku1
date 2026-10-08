package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.QuadraticCurve;
import com.example.starter.domain.RouteKind;

import java.util.List;

/**
 * 航线当前状态记录。
 *
 * @param routeId 航线唯一标识
 * @param version 当前航线版本（从 1 开始）
 * @param kind    几何类型：LINE 直线；CURVE 二次曲线
 * @param points  LINE 类型当前版本有序航点；CURVE 类型为空列表
 * @param curves  CURVE 类型当前版本有序曲线段（首尾相接）；LINE 类型为空列表
 */
public record RoutePo(String routeId, int version, RouteKind kind,
                      List<Point> points, List<QuadraticCurve> curves) {
}
