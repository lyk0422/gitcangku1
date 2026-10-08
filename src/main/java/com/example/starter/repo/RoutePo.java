package com.example.starter.repo;

import com.example.starter.domain.CurveSegment;
import com.example.starter.domain.Point;
import com.example.starter.domain.RouteKind;

import java.util.List;

/**
 * 航线当前状态记录。
 *
 * @param routeId  航线唯一标识
 * @param version  当前航线版本（从 1 开始）
 * @param kind     航线几何类型：LINE 折线 / QUADRATIC 二次曲线
 * @param points   LINE 类型的当前版本有序航点；QUADRATIC 类型为空列表
 * @param segments QUADRATIC 类型的当前版本有序曲线段（1~5 段）；LINE 类型为空列表
 */
public record RoutePo(String routeId, int version, RouteKind kind,
                      List<Point> points, List<CurveSegment> segments) {
}
