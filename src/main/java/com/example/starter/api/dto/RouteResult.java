package com.example.starter.api.dto;

/**
 * 航线创建/替换结果。
 *
 * @param routeId 航线标识
 * @param version 操作后的航线版本
 * @param window  整条航线统一飞行窗口；null 表示全时有效
 */
public record RouteResult(String routeId, int version, TimeWindowDto window) {

    /** 全时窗口结果。 */
    public RouteResult(String routeId, int version) {
        this(routeId, version, null);
    }
}
