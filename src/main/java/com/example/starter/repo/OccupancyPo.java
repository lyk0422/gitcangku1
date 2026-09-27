package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 空域占用持久化记录。
 *
 * @param occupancyId      占用唯一标识（客户端指定）
 * @param routeId          航线标识
 * @param reviewId         占用依据的 CLEAR 审查标识
 * @param routeVersion     登记时固化的航线版本
 * @param airspaceVersion  登记时固化的空域版本
 * @param kind             REGULAR / EMERGENCY
 * @param startUtc         占用起点 epoch 毫秒（UTC，含）
 * @param endUtc           占用终点 epoch 毫秒（UTC，不含）
 * @param altMin           最低高度（含），米
 * @param altMax           最高高度（不含），米
 * @param status           ACTIVE / YIELDED
 * @param yieldable        常规占用是否可让渡
 * @param departed         是否已起飞
 * @param corridorId       让渡走廊标识；null 表示未让渡
 * @param parentOccupancyId 重新申请的原占用标识；null 表示原始登记
 * @param pointsSnapshot   审查航点不可变快照
 * @param createdAt        登记时间 epoch 毫秒（UTC）
 * @param departedAt       起飞时间 epoch 毫秒（UTC）；null 表示未起飞
 */
public record OccupancyPo(String occupancyId, String routeId, String reviewId,
                          int routeVersion, long airspaceVersion, String kind,
                          long startUtc, long endUtc, int altMin, int altMax,
                          String status, boolean yieldable, boolean departed,
                          String corridorId, String parentOccupancyId,
                          List<Point> pointsSnapshot, long createdAt, Long departedAt) {
}
