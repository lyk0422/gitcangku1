package com.example.starter.baggage.api.dto;

/**
 * 航段视图。
 *
 * @param legId       航段标识
 * @param origin      始发站
 * @param destination 到达站
 * @param status      状态：OPEN / SEALED / ARRIVED
 * @param version     当前版本号
 */
public record LegView(String legId, String origin, String destination, String status, int version) {
}
