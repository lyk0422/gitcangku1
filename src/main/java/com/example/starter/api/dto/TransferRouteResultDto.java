package com.example.starter.api.dto;

/**
 * 转配后单条航线的版本结果。
 *
 * @param routeId    航线标识
 * @param oldVersion 转配前版本
 * @param newVersion 转配后版本
 * @param reviewId   冻结的审查依据记录标识
 */
public record TransferRouteResultDto(String routeId, int oldVersion, int newVersion,
                                     String reviewId) {
}
