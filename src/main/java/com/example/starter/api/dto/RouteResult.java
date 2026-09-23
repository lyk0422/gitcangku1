package com.example.starter.api.dto;

/**
 * 航线创建/替换结果。
 *
 * @param routeId     航线标识
 * @param version     操作后的航线版本
 * @param windowStart 航线整体飞行窗口开始时刻，epoch 毫秒（UTC），区间含；null 表示全时有效
 * @param windowEnd   航线整体飞行窗口结束时刻，epoch 毫秒（UTC），区间不含；null 表示全时有效
 */
public record RouteResult(String routeId, int version,
                          Long windowStart, Long windowEnd) {
}
