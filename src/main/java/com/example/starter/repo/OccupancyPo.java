package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 空域时空占用记录。
 *
 * @param occupancyId    占用唯一标识
 * @param routeId        占用航线标识
 * @param routeVersion   占用申请时的航线版本
 * @param routeType      航线类型快照：REGULAR / EMERGENCY
 * @param startAt        占用起点（含），epoch 毫秒（UTC）
 * @param endAt          占用终点（不含），epoch 毫秒（UTC）
 * @param altitudeLower  占用高度下限（含），单位米
 * @param altitudeUpper  占用高度上限（不含），单位米
 * @param yieldable      常规占用是否可被应急走廊强制让渡
 * @param status         ACTIVE / YIELDED
 * @param departed       是否已起飞登记
 * @param departedAt     起飞登记时间（epoch 毫秒）；null 表示未起飞
 * @param reviewId       占用依据的当前有效 CLEAR 审核记录
 * @param pointsSnapshot 占用时航点不可变快照
 * @param requestId      创建占用的写操作请求标识
 * @param createdAt      创建时间（epoch 毫秒）
 */
public record OccupancyPo(String occupancyId, String routeId, int routeVersion, String routeType,
                          long startAt, long endAt, int altitudeLower, int altitudeUpper,
                          boolean yieldable, String status, boolean departed, Long departedAt,
                          String reviewId, List<Point> pointsSnapshot,
                          String requestId, long createdAt) {
}
