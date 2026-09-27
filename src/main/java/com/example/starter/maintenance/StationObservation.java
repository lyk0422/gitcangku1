package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 站点观测原始记录：提交时按提交时刻匹配当时生效窗口并固化维护标记与窗口版本，
 * 后续窗口删除、更正或补录均不改写本记录。
 *
 * @param observationId             观测记录唯一标识
 * @param stationId                 所属观测站标识
 * @param clusterId                 所属冲突簇标识；未归簇的散装观测为 null
 * @param submittedAtUtc            观测提交时刻（UTC，窗口匹配按此时刻）
 * @param payload                   观测原始内容原文（始终保存，命中维护窗口也不丢弃）
 * @param maintenanceAffected       是否命中维护窗口（MAINTENANCE_AFFECTED）
 * @param matchedWindowId           命中的维护窗口标识；未命中为 null
 * @param matchedMaintenanceVersion 命中窗口的维护版本号；未命中为 null
 * @param createdAtUtc              记录落库时刻（UTC）
 */
public record StationObservation(
        String observationId,
        String stationId,
        String clusterId,
        Instant submittedAtUtc,
        String payload,
        boolean maintenanceAffected,
        String matchedWindowId,
        Integer matchedMaintenanceVersion,
        Instant createdAtUtc) {
}
