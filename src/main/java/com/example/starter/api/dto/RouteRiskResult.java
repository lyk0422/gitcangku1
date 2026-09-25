package com.example.starter.api.dto;

/**
 * 航线跑道风险查询结果：新关闭窗口命中时固化的不可变窗口快照。
 *
 * @param routeId        航线标识
 * @param status         航线当前状态（查询时应为 RUNWAY_RISK）
 * @param closureId      命中的关闭窗口标识（快照）
 * @param runwayId       跑道标识（快照）
 * @param segment        命中航段：DEPARTURE / ARRIVAL
 * @param startUtc       关闭开始（含）快照，UTC epoch 毫秒
 * @param endUtc         关闭结束（不含）快照，UTC epoch 毫秒
 * @param allowEmergency 窗口是否允许紧急例外（快照）
 * @param operator       登记操作者（快照）
 * @param runwayVersion  关闭生效后的跑道版本（快照）
 * @param createdAt      风险固化时间（epoch 毫秒）
 */
public record RouteRiskResult(String routeId, String status, String closureId, String runwayId,
                              String segment, long startUtc, long endUtc, boolean allowEmergency,
                              String operator, int runwayVersion, long createdAt) {
}
