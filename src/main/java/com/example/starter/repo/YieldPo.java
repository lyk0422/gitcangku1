package com.example.starter.repo;

/**
 * 走廊让渡历史记录（不可变）。
 *
 * @param yieldId         让渡记录唯一标识
 * @param corridorId      触发让渡的应急走廊标识
 * @param corridorVersion 让渡发生时的走廊版本
 * @param occupationId    被让渡的常规占用标识
 * @param routeId         被让渡航线标识
 * @param reviewId        原占用的授权审查记录标识
 * @param routeVersion    原审查固化的航线版本
 * @param reason          让渡原因
 * @param createdAt       让渡时间（epoch 毫秒，UTC）
 */
public record YieldPo(String yieldId, String corridorId, int corridorVersion,
                      String occupationId, String routeId, String reviewId,
                      int routeVersion, String reason, long createdAt) {
}
