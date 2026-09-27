package com.example.starter.api.dto;

/**
 * 创建空域占用结果。
 *
 * @param occupancyId    占用唯一标识
 * @param routeId        占用航线标识
 * @param routeVersion   占用申请时的航线版本
 * @param routeType      航线类型：REGULAR / EMERGENCY
 * @param status         占用状态：ACTIVE
 * @param durationMillis 占用时长（endAt - startAt），单位毫秒
 */
public record OccupancyResult(
        String occupancyId,
        String routeId,
        int routeVersion,
        String routeType,
        String status,
        long durationMillis) {
}
