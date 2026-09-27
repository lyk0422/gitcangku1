package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 观测站响应。
 *
 * @param stationId    观测站唯一标识
 * @param enabledAtUtc 站点启用时刻（UTC）
 * @param createdAtUtc 站点登记完成时刻（UTC）
 */
public record StationResponse(
        String stationId,
        Instant enabledAtUtc,
        Instant createdAtUtc) {

    /**
     * 由领域对象构造响应。
     */
    public static StationResponse of(Station station) {
        return new StationResponse(station.stationId(), station.enabledAtUtc(), station.createdAtUtc());
    }
}
