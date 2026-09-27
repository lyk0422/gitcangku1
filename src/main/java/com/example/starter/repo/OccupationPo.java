package com.example.starter.repo;

/**
 * 占用持久化记录（不可变）。
 *
 * @param occupationId     占用记录唯一标识
 * @param reviewId         所依据的审核记录标识
 * @param routeId          占用航线标识
 * @param routeVersion     审核时固化的航线版本
 * @param airspaceVersion  审核时固化的空域版本
 * @param forecastVersion  审核时固化的预测气象版本；null 表示审核未做气象检查
 * @param standardsVersion 审核时固化的气象标准版本；null 表示审核未做气象检查
 * @param requestId        创建占用的写操作请求标识
 * @param createdAt        创建时间，epoch 毫秒（UTC）
 */
public record OccupationPo(String occupationId, String reviewId, String routeId,
                           int routeVersion, long airspaceVersion,
                           Long forecastVersion, Long standardsVersion,
                           String requestId, long createdAt) {
}
