package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 运动间隔许可持久化记录（不可变证据，撤销仅改状态与撤销时间）。
 *
 * @param permitId         许可唯一标识
 * @param routeId          引用的航线标识
 * @param routeVersion     许可锁定的航线版本；航线后续替换不移动已许可轨迹
 * @param operator         申请操作者
 * @param airspaceVersion  许可审查时读取的一致空域版本（证据，不改写）
 * @param startEpochSecond 生效起始时刻，UTC epoch 整秒（含）
 * @param endEpochSecond   生效结束时刻，UTC epoch 整秒（不含）
 * @param pointsSnapshot   许可时两点不可变快照，单位米、最多3位小数
 * @param status           ACTIVE / REVOKED
 * @param requestId        创建许可的写操作请求标识
 * @param createdAt        创建时间（epoch 毫秒，UTC）
 * @param revokedAt        撤销时间（epoch 毫秒，UTC）；null 表示仍有效
 */
public record PermitPo(String permitId, String routeId, int routeVersion, String operator,
                       long airspaceVersion, long startEpochSecond, long endEpochSecond,
                       List<Point> pointsSnapshot, String status, String requestId,
                       long createdAt, Long revokedAt) {
}