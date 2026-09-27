package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;

/**
 * 发布单视图。前 10 个字段保持历史顺序与语义稳定；新增目标策略字段仅在启用策略时非空：
 * target/tagDictVersion/resolvedAtUtc 同时为空表示未启用目标策略（按型号动态匹配）。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, String operator, TargetExpressionView target,
                          Long tagDictVersion, String resolvedAtUtc, Integer targetSize) {

    public static ReleaseView of(ReleaseOrder order) {
        return of(order, null, null);
    }

    /**
     * @param targetExpression 已解析的表达式视图；order 未启用策略时为 null
     * @param targetSize       快照成员数；未启用策略或不需要展示时为 null
     */
    public static ReleaseView of(ReleaseOrder order, TargetExpressionView targetExpression, Integer targetSize) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(), order.operator(), targetExpression,
                order.tagDictVersion(), order.resolvedAtUtc(), targetSize);
    }
}
