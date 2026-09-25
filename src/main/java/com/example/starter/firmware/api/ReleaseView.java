package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。regionLimit 为各区域同时进行中任务数上限，null 表示不限流。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, Integer regionLimit, String status) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.regionLimit(), order.status().name());
    }
}
