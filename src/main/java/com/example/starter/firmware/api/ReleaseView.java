package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。baseDigest/patchDigest/targetDigest 仅增量发布（DELTA）非空。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          String releaseType, String baseDigest, String patchDigest, String targetDigest,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.releaseType().name(), order.baseDigest(), order.patchDigest(),
                order.targetDigest(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound());
    }
}