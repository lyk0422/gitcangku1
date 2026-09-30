package com.example.starter.api.dto;

/**
 * 备降机场创建结果。
 *
 * @param airportId 机场标识
 * @param capacity  同一窗口容量（恒 &gt;=1）
 */
public record AirportResult(String airportId, int capacity) {
}
