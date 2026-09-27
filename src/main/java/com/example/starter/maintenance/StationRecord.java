package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 观测站记录：对应 observation_station 表的一行。
 *
 * @param stationId      观测站唯一标识
 * @param activatedAtUtc 站点启用时刻（UTC）；维护窗口开始时刻不得早于此时刻
 */
public record StationRecord(
        String stationId,
        Instant activatedAtUtc) {
}
