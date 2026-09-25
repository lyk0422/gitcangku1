package com.example.starter.repo;

/**
 * 高度层占用持久化记录。取消后历史保留（status 置 CANCELLED）。
 *
 * @param occupancyId     占用记录唯一标识
 * @param reviewId        关联的审核记录标识
 * @param routeId         占用航线标识
 * @param routeVersion    占用关联的航线版本
 * @param airspaceVersion 占用关联的空域版本
 * @param zoneId          占用区域标识
 * @param bandId          占用高度带标识
 * @param cruiseAltitudeM 占用航线巡航高度快照，米
 * @param startAt         占用起始时刻，epoch 毫秒（UTC，含）
 * @param endAt           占用结束时刻，epoch 毫秒（UTC，不含）
 * @param status          ACTIVE / CANCELLED
 * @param requestId       创建占用的请求标识
 * @param createdAt       创建时间（epoch 毫秒）
 * @param cancelledAt     取消时间（epoch 毫秒）；null 表示未取消
 */
public record OccupancyPo(String occupancyId, String reviewId, String routeId, int routeVersion,
                          long airspaceVersion, String zoneId, String bandId,
                          int cruiseAltitudeM, long startAt, long endAt,
                          String status, String requestId, long createdAt, Long cancelledAt) {
}
