package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 观测站：维护窗口不得早于启用时刻登记。
 *
 * @param stationId    观测站唯一标识
 * @param enabledAtUtc 站点启用时刻（UTC）；维护窗口开始时刻不得早于该时刻
 * @param createdAtUtc 站点登记完成时刻（UTC）
 */
public record Station(
        String stationId,
        Instant enabledAtUtc,
        Instant createdAtUtc) {
}
