package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId     航线唯一标识
 * @param version     当前航线版本（从 1 开始）
 * @param points      当前版本有序航点
 * @param windowStart 整体飞行窗口起始（UTC epoch 毫秒，左闭）；与 windowEnd 成对为 null 表示全时有效
 * @param windowEnd   整体飞行窗口结束（UTC epoch 毫秒，右开）
 */
public record RoutePo(String routeId, int version, List<Point> points,
                      Long windowStart, Long windowEnd) {
}
