package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 站停登记视图（草稿/明细）。null 时刻语义与请求一致，JSON 中稳定输出为 null。
 */
public record StationStopView(String trainNo, String stationId,
                              Instant arrivalUtc, Instant departureUtc) {
}
