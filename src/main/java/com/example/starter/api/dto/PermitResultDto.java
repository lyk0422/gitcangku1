package com.example.starter.api.dto;

import java.util.List;

/**
 * 许可查询结果。历史许可保留许可时的不可变轨迹快照与空域版本证据。
 *
 * @param permitId         许可唯一标识
 * @param routeId          引用的航线标识
 * @param routeVersion     许可锁定的航线版本
 * @param airspaceVersion  许可审查时读取的一致空域版本
 * @param operator         申请操作者
 * @param startEpochSecond 生效起始时刻，UTC epoch 整秒（含）
 * @param endEpochSecond   生效结束时刻，UTC epoch 整秒（不含）
 * @param status           ACTIVE / REVOKED
 * @param pointsSnapshot   许可时两点不可变快照（单位米）
 * @param createdAt        创建时间（epoch 毫秒，UTC）
 * @param revokedAt        撤销时间（epoch 毫秒，UTC）；未撤销为 null
 */
public record PermitResultDto(String permitId, String routeId, Integer routeVersion,
                              Long airspaceVersion, String operator,
                              Long startEpochSecond, Long endEpochSecond, String status,
                              List<RoutePointDto> pointsSnapshot,
                              Long createdAt, Long revokedAt) {
}