package com.example.starter.api.dto;

/**
 * 占用创建/起飞登记结果。
 *
 * @param occupationId    占用标识
 * @param status          操作后状态：ACTIVE / DEPARTED
 * @param kind            占用类型：REGULAR / EMERGENCY
 * @param routeId         占用航线标识
 * @param routeVersion    占用审查的航线版本
 * @param reviewId        授权本次占用的不可变审查记录标识
 * @param airspaceVersion 占用审查时的全局空域版本
 */
public record OccupationResult(String occupationId, String status, String kind,
                               String routeId, int routeVersion, String reviewId,
                               long airspaceVersion) {
}
