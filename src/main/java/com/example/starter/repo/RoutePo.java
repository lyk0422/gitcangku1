package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 航线当前状态记录（点列为当前版本的点）。
 *
 * @param routeId  航线唯一标识
 * @param version  当前航线版本（从 1 开始）
 * @param points   当前版本有序航点
 * @param status   PENDING / APPROVED / DISPLACED / DEPARTED
 * @param priority 最近一次审查声明的备降优先级（NORMAL / EMERGENCY）
 * @param eventNo  EMERGENCY 的事件编号；否则为 null
 */
public record RoutePo(String routeId, int version, List<Point> points,
                      String status, String priority, String eventNo) {
}
