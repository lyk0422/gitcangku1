package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 空域占用持久化记录。
 *
 * @param occupationId    占用唯一标识
 * @param routeId         占用航线标识
 * @param routeVersion    占用时审查的航线版本
 * @param kind            REGULAR / EMERGENCY
 * @param startUtc        占用起点（含），epoch 毫秒（UTC）
 * @param endUtc          占用终点（不含），epoch 毫秒（UTC）
 * @param altMin          占用高度下限（含），米
 * @param altMax          占用高度上限（不含），米
 * @param yieldable       是否可被应急走廊强制让渡
 * @param status          ACTIVE / YIELDED / DEPARTED
 * @param reviewId        授权本次占用的不可变审查记录标识
 * @param airspaceVersion 占用审查时的全局空域版本
 * @param pointsSnapshot  占用时航点不可变快照
 * @param supersededBy    重新申请生成的新占用标识；null 表示未被接替
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 * @param departedAt      起飞登记时间（epoch 毫秒，UTC）；null 表示未起飞
 */
public record OccupationPo(String occupationId, String routeId, int routeVersion, String kind,
                           long startUtc, long endUtc, int altMin, int altMax,
                           boolean yieldable, String status, String reviewId,
                           long airspaceVersion, List<Point> pointsSnapshot,
                           String supersededBy, long createdAt, Long departedAt) {
}
