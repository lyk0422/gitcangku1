package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。exemptionVersion 为模块豁免版本号，从0开始，每次豁免整体修改成功加一。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, int exemptionVersion) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(), order.exemptionVersion());
    }
}
