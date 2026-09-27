package com.example.starter.api.dto;

/**
 * 占用创建结果。全部版本均为审核时固化的值。
 *
 * @param occupationId     占用记录唯一标识
 * @param reviewId         所依据的审核记录标识
 * @param routeId          占用航线标识
 * @param routeVersion     审核时固化的航线版本
 * @param airspaceVersion  审核时固化的空域版本
 * @param forecastVersion  审核时固化的预测气象版本；null 表示审核未做气象检查
 * @param standardsVersion 审核时固化的气象标准版本；null 表示审核未做气象检查
 */
public record OccupationResult(
        String occupationId,
        String reviewId,
        String routeId,
        Integer routeVersion,
        Long airspaceVersion,
        Long forecastVersion,
        Long standardsVersion) {
}
