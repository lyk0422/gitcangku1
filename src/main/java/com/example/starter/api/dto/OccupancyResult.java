package com.example.starter.api.dto;

/**
 * 空域占用结果。
 *
 * @param occupancyId     占用记录唯一标识
 * @param reviewId        来源审核记录标识
 * @param routeId         占用航线标识
 * @param routeVersion    占用时固化的航线版本（来自审核快照）
 * @param airspaceVersion 占用时固化的空域版本（来自审核快照）
 * @param dutyVersion     占用时固化的气象值班版本（来自审核快照）
 * @param forecastVersion 占用时固化的预测气象版本（来自审核快照）；null 表示无预测
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record OccupancyResult(
        String occupancyId,
        String reviewId,
        String routeId,
        int routeVersion,
        long airspaceVersion,
        long dutyVersion,
        Long forecastVersion,
        long createdAt) {
}