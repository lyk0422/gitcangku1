package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测站：对应 station 表一行。
 *
 * @param stationId     观测站唯一标识
 * @param activatedAtUtc 站点启用时刻（UTC）；维护窗口开始时刻不得早于该时刻
 */
public record Station(
        String stationId,
        Instant activatedAtUtc) {
}
