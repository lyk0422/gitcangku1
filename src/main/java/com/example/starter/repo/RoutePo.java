package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId        航线唯一标识
 * @param version        当前航线版本（从 1 开始）
 * @param points         当前版本有序航点
 * @param cruiseAltitude 巡航高度（米）；null 表示未登记高度
 * @param startTime      UTC 时段起始，epoch 毫秒（含）；null 表示未登记
 * @param endTime        UTC 时段结束，epoch 毫秒（不含）；null 表示未登记
 */
public record RoutePo(String routeId, int version, List<Point> points,
                      Integer cruiseAltitude, Long startTime, Long endTime) {
}
