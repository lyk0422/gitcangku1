package com.example.starter.repo;

/**
 * 航线跑道关闭风险记录：新关闭窗口命中未来已批准 NORMAL 航线时固化的窗口快照。
 *
 * @param routeId        风险航线标识
 * @param closureId      命中的关闭窗口标识（快照）
 * @param runwayId       跑道标识（快照）
 * @param segment        命中航段：DEPARTURE / ARRIVAL
 * @param startUtc       关闭开始（含）快照，epoch 毫秒（UTC）
 * @param endUtc         关闭结束（不含）快照，epoch 毫秒（UTC）
 * @param allowEmergency 窗口是否允许紧急例外（快照）
 * @param operator       登记操作者（快照）
 * @param runwayVersion  关闭生效后的跑道版本（快照）
 * @param createdAt      风险固化时间（epoch 毫秒）
 */
public record RouteRiskPo(String routeId, String closureId, String runwayId, String segment,
                          long startUtc, long endUtc, boolean allowEmergency, String operator,
                          int runwayVersion, long createdAt) {
}
