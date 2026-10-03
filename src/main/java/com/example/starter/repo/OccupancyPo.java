package com.example.starter.repo;

/**
 * 航线空域占用记录（不可变）。
 *
 * @param occupancyId     占用记录唯一标识
 * @param reviewId        所依据的审查记录标识
 * @param routeId         占用航线标识
 * @param routeVersion    审查时固化的航线版本
 * @param airspaceVersion 审查时固化的空域版本
 * @param weatherVersion  审查时固化的气象版本；null 表示审查未提交气象版本
 * @param requestId       创建占用的写操作请求标识
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record OccupancyPo(String occupancyId, String reviewId, String routeId, int routeVersion,
                          long airspaceVersion, Long weatherVersion,
                          String requestId, long createdAt) {
}