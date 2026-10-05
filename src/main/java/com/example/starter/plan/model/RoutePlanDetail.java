package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 寻径发布冻结快照：图版本、起终站与路径区段序列；后续换图不回写。
 *
 * @param planId         所属计划 id
 * @param graphVersion   发布时冻结的线路图版本
 * @param trainNo        列车编号
 * @param fromStation    起点站
 * @param toStation      终点站
 * @param departUtc      出发时刻（UTC 整分钟）
 * @param arriveUtc      到达时刻
 * @param totalMinutes   路径总行驶分钟
 * @param segmentIdsJson 路径区段编号序列（JSON 数组，按行驶顺序）
 */
public record RoutePlanDetail(long planId, int graphVersion, String trainNo, String fromStation,
                              String toStation, Instant departUtc, Instant arriveUtc,
                              int totalMinutes, String segmentIdsJson) {
}
