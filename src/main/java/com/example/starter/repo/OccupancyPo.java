package com.example.starter.repo;

/**
 * 空域占用持久化记录（不可变）。
 *
 * @param occupancyId     占用记录唯一标识
 * @param reviewId        来源审核记录标识
 * @param routeId         占用航线标识
 * @param routeVersion    占用时固化的航线版本（来自审核快照）
 * @param airspaceVersion 占用时固化的空域版本（来自审核快照）
 * @param dutyVersion     占用时固化的气象值班版本（来自审核快照）
 * @param forecastVersion 占用时固化的预测气象版本（来自审核快照）；null 表示无预测
 * @param operator        创建占用的操作者
 * @param requestId       创建占用的写操作请求标识
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record OccupancyPo(String occupancyId, String reviewId, String routeId, int routeVersion,
                          long airspaceVersion, long dutyVersion, Long forecastVersion,
                          String operator, String requestId, long createdAt) {
}