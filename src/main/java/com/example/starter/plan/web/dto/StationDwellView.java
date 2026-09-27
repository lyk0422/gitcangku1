package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 中间站到发登记视图，区间左闭右开，时刻为 UTC。
 * null 不适用：登记行始终包含完整到发时刻。
 */
public record StationDwellView(String trainNo, String stationId,
                               Instant arrivalUtc, Instant departureUtc) {
}
