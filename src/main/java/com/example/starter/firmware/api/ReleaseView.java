package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。targetSecurityIndex 为发布附带的目标安全索引，旧发布未填为 null。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, Integer targetSecurityIndex) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(), order.targetSecurityIndex());
    }
}
