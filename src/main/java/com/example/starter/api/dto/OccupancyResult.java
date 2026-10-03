package com.example.starter.api.dto;

/**
 * 占用创建/查询结果。版本均为审查时固化的值，后续修订不改写。
 *
 * @param occupancyId     占用记录唯一标识
 * @param reviewId        所依据的审查记录标识
 * @param routeId         占用航线标识
 * @param routeVersion    审查时固化的航线版本
 * @param airspaceVersion 审查时固化的空域版本
 * @param weatherVersion  审查时固化的气象版本；null 表示审查未提交气象版本
 * @param createdAt       创建时间，epoch 毫秒（UTC）
 */
public record OccupancyResult(String occupancyId, String reviewId, String routeId,
                              int routeVersion, long airspaceVersion, Long weatherVersion,
                              long createdAt) {
}