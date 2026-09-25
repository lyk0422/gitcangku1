package com.example.starter.repo;

/**
 * 高度层占用持久化记录。
 *
 * @param occupancyId 占用记录唯一标识
 * @param reviewId    关联的 CLEAR 审核记录标识
 * @param routeId     占用航线标识
 * @param zoneId      占用的区域标识
 * @param bandLower   占用高度带下限（含），米
 * @param bandUpper   占用高度带上限（不含），米；创建时快照，不随后续改带改写
 * @param startTime   占用 UTC 时段起始，epoch 毫秒（含）
 * @param endTime     占用 UTC 时段结束，epoch 毫秒（不含）
 * @param status      ACTIVE / CANCELLED
 * @param createdAt   创建时间，epoch 毫秒（UTC）
 * @param cancelledAt 取消时间，epoch 毫秒（UTC）；null 表示未取消
 */
public record OccupancyPo(String occupancyId, String reviewId, String routeId, String zoneId,
                          int bandLower, int bandUpper, long startTime, long endTime,
                          String status, long createdAt, Long cancelledAt) {
}
