package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 观测站响应。
 *
 * @param stationId      观测站唯一标识
 * @param activatedAtUtc 站点启用时刻（UTC，ISO-8601）
 */
public record StationResponse(
        String stationId,
        Instant activatedAtUtc) {

    public static StationResponse of(StationRecord record) {
        return new StationResponse(record.stationId(), record.activatedAtUtc());
    }
}
