package com.example.starter.maintenance;

import java.time.Instant;

/**
 * 合并快照中的单条观测证据：固化生成快照时该观测的维护标记与命中窗口版本。
 *
 * @param observationId             观测记录唯一标识
 * @param submittedAtUtc            观测提交时刻（UTC）
 * @param payload                   观测原始内容原文
 * @param maintenanceAffected       该观测是否受维护影响（快照固化值）
 * @param matchedWindowId           命中窗口标识（快照固化值）；未命中为 null
 * @param matchedMaintenanceVersion 命中窗口维护版本号（快照固化值）；未命中为 null
 */
public record SnapshotEvidence(
        String observationId,
        Instant submittedAtUtc,
        String payload,
        boolean maintenanceAffected,
        String matchedWindowId,
        Integer matchedMaintenanceVersion) {
}
