package com.example.starter.repo;

/**
 * 空域占用持久化记录（不可变；全部版本取自审核时固化快照）。
 *
 * @param occupationId    占用记录唯一标识
 * @param reviewId        引用的审核记录标识
 * @param routeId         占用航线标识
 * @param routeVersion    固化的航线版本
 * @param airspaceVersion 固化的空域版本
 * @param weatherVersion  固化的气象标准版本
 * @param forecastVersion 固化的预测气象版本；null 表示审核未涉及预测
 * @param dutyVersion     固化的值班版本
 * @param requestId       创建占用的请求标识
 * @param createdAt       创建时间，epoch 毫秒（UTC）
 */
public record OccupationPo(String occupationId, String reviewId, String routeId,
                           int routeVersion, long airspaceVersion, long weatherVersion,
                           Long forecastVersion, long dutyVersion,
                           String requestId, long createdAt) {
}
