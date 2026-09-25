package com.example.starter.repo;

import com.example.starter.domain.FlightPlan;
import com.example.starter.domain.Point;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId    航线唯一标识
 * @param version    当前航线版本（从 1 开始）
 * @param points     当前版本有序航点
 * @param status     生命周期状态：DRAFT / APPROVED / RUNWAY_RISK / DEPARTED / CANCELLED
 * @param flightPlan 起降计划；null 表示无飞行计划（不参与跑道关闭与容量检查）
 */
public record RoutePo(String routeId, int version, List<Point> points,
                      String status, FlightPlan flightPlan) {
}
