package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId         航线唯一标识
 * @param version         当前航线版本（从 1 开始）
 * @param points          当前版本有序航点
 * @param cruiseAltitudeM 巡航高度，米
 * @param startAt         巡航起始时刻，epoch 毫秒（UTC，含）
 * @param endAt           巡航结束时刻，epoch 毫秒（UTC，不含）
 */
public record RoutePo(String routeId, int version, List<Point> points,
                      int cruiseAltitudeM, long startAt, long endAt) {
}
