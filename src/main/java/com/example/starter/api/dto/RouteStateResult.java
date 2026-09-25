package com.example.starter.api.dto;

/**
 * 航线状态操作结果（起飞 / 取消 / 转紧急例外）。
 *
 * @param routeId 航线标识
 * @param version 当前航线版本
 * @param status  操作后的航线状态
 */
public record RouteStateResult(String routeId, int version, String status) {
}
