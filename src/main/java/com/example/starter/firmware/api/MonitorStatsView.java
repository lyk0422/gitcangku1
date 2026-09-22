package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.MonitoringStats;
import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 当前监控轮次统计视图（只读）。
 */
public record MonitorStatsView(long releaseId, String status, int monitorRound, int sampleFloor,
                               int failureThresholdPercent, long successCount, long failureCount,
                               long sampleCount) {

    public static MonitorStatsView of(ReleaseOrder order, MonitoringStats stats) {
        return new MonitorStatsView(order.id(), order.status().name(), order.monitorRound(),
                order.sampleFloor(), order.failureThresholdPercent(),
                stats.successCount(), stats.failureCount(), stats.sampleCount());
    }
}
