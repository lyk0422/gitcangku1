package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

import java.util.List;

/**
 * 发布单视图。target 为创建时固化的标签表达式与目标快照诊断信息；
 * labelDictVersion 为解析时刻字典版本，后续标签修订不改变该值。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, TargetSnapshotView target) {

    public static ReleaseView of(ReleaseOrder order, TargetSnapshotView target) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(), target);
    }
}
