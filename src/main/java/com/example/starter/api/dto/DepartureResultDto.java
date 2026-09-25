package com.example.starter.api.dto;

/**
 * 起飞登记结果。
 *
 * @param clearanceId 批件标识
 * @param routeId     航线标识
 * @param status      登记后状态：DEPARTED
 * @param departedAt  登记时间，epoch 毫秒（UTC）
 */
public record DepartureResultDto(
        String clearanceId,
        String routeId,
        String status,
        Long departedAt) {
}
