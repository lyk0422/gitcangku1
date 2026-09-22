package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 当前监控轮次统计视图（只读）。
 */
public record MonitorView(long releaseId, String status, int monitorRound, int roundSuccess, int roundFailed,
                          int sampleFloor, int failureThresholdPercent) {

    public static MonitorView of(ReleaseOrder order) {
        return new MonitorView(order.id(), order.status().name(), order.monitorRound(),
                order.roundSuccess(), order.roundFailed(), order.sampleFloor(),
                order.failureThresholdPercent());
    }
}
