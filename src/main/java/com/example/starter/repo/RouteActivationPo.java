package com.example.starter.repo;

/**
 * 航线版本激活记录。
 *
 * @param routeId       航线标识
 * @param version       被激活的航线版本
 * @param status        状态：ACTIVE 生效占用容量；SUSPENDED 已停用
 * @param reviewId      激活依据的审查记录标识（结论 CLEAR）
 * @param departureTime 计划起飞时刻，epoch 秒（UTC）
 * @param speedMps      计划地速，米/秒
 * @param requestId     激活写操作请求标识
 * @param createdAt     激活时间，epoch 毫秒（UTC）
 */
public record RouteActivationPo(String routeId, int version, String status, String reviewId,
                                long departureTime, double speedMps, String requestId,
                                long createdAt) {
}
