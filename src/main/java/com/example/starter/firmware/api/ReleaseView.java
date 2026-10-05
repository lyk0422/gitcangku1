package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。
 */
public record ReleaseView(long releaseId, int version, String kind, String model, String fromVersion,
                          String toVersion, int ratio, String status, int sampleFloor,
                          int failureThresholdPercent, int monitorRound) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.kind().name(), order.model(),
                order.fromVersion(), order.toVersion(), order.ratio(), order.status().name(),
                order.sampleFloor(), order.failureThresholdPercent(), order.monitorRound());
    }
}