package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId        航线唯一标识
 * @param version        当前航线版本（从 1 开始）
 * @param points         当前版本有序航点
 * @param cruiseAltitude 巡航高度（米）
 * @param startUtc       UTC 起始时刻（含），epoch 毫秒
 * @param endUtc         UTC 结束时刻（不含），epoch 毫秒
 */
public record RoutePo(String routeId, int version, List<Point> points,
                      int cruiseAltitude, long startUtc, long endUtc) {
}
