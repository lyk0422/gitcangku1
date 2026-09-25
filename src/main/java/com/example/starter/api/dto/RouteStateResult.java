package com.example.starter.api.dto;

/**
 * 航线状态视图（起飞登记、被置换航线查询等返回）。
 *
 * @param routeId 航线唯一标识
 * @param version 当前航线版本
 * @param status  PENDING / APPROVED / DISPLACED / DEPARTED
 */
public record RouteStateResult(String routeId, int version, String status) {
}
