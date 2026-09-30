package com.example.starter.api.dto;

/**
 * 备降机场创建结果。
 *
 * @param airportId 机场标识
 * @param capacity  同一时段窗口容量
 */
public record AirportResult(String airportId, int capacity) {
}
