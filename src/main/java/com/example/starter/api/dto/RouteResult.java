package com.example.starter.api.dto;

/**
 * 航线创建/替换结果。
 *
 * @param routeId 航线标识
 * @param version 操作后的航线版本
 * @param window  航线整体飞行窗口（UTC 毫秒，左闭右开）；全时时刻均为 null
 */
public record RouteResult(String routeId, int version, TimeWindowDto window) {
}
