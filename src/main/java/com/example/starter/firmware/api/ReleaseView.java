package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。fragments 为按序号规范排序的登记分片摘要。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, String packageDigest, int fragmentCount,
                          java.util.List<FragmentView> fragments) {

    public static ReleaseView of(ReleaseOrder order, java.util.List<FragmentView> fragments) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(), order.packageDigest(),
                order.fragmentCount(), fragments);
    }
}
