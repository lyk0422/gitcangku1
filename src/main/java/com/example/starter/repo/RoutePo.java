package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.TimeWindow;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId 航线唯一标识
 * @param version 当前航线版本（从 1 开始）
 * @param points  当前版本有序航点
 * @param window  当前版本整体飞行窗口（UTC 毫秒，左闭右开；全时为 {@link TimeWindow#ALL_TIME}）
 */
public record RoutePo(String routeId, int version, List<Point> points, TimeWindow window) {
}
