package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。shardCount/fullDigest 为已登记分片清单信息，未登记为 null。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, Integer shardCount, String fullDigest) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(), order.shardCount(),
                order.fullDigest());
    }
}
