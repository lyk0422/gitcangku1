package com.example.starter.repo;

/**
 * 高度层占用持久化记录。时间窗 {@code [startUtc, endUtc)} 左闭右开，单位 epoch 毫秒（UTC）。
 *
 * @param occupationId   占用记录唯一标识
 * @param reviewId       关联审查记录标识
 * @param routeId        占用航线标识
 * @param zoneId         占用的禁飞区标识
 * @param bandId         占用的高度带标识
 * @param startUtc       占用 UTC 起始时刻（含）
 * @param endUtc         占用 UTC 结束时刻（不含）
 * @param cruiseAltitude 创建时巡航高度快照（米）
 * @param status         ACTIVE / CANCELLED
 * @param requestId      创建占用的请求标识
 * @param createdAt      创建时间（epoch 毫秒，UTC）
 * @param cancelledAt    取消时间（epoch 毫秒，UTC）；null 表示未取消
 */
public record OccupationPo(String occupationId, String reviewId, String routeId, String zoneId,
                           String bandId, long startUtc, long endUtc, int cruiseAltitude,
                           String status, String requestId, long createdAt, Long cancelledAt) {
}
