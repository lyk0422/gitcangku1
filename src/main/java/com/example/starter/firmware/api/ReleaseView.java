package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, boolean allowSkip, int sampleFloor,
                          int failureThresholdPercent, int monitorRound) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.allowSkip(),
                order.sampleFloor(), order.failureThresholdPercent(), order.monitorRound());
    }
}
