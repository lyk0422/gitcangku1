package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;

/**
 * 证据查询中的单条观测：显示快照固化的维护标记与命中窗口版本。
 *
 * @param observationId             观测记录唯一标识
 * @param submittedAtUtc            观测提交时刻（UTC）
 * @param payload                   观测原始内容原文
 * @param maintenanceAffected       是否受维护影响（快照固化值）
 * @param matchedWindowId           命中窗口标识（快照固化值）；未命中为 null
 * @param matchedMaintenanceVersion 命中窗口维护版本号（快照固化值）；未命中为 null
 */
public record EvidenceResponse(
        String observationId,
        Instant submittedAtUtc,
        String payload,
        boolean maintenanceAffected,
        String matchedWindowId,
        Integer matchedMaintenanceVersion) {

    /**
     * 由快照证据构造响应。
     */
    public static EvidenceResponse of(SnapshotEvidence evidence) {
        return new EvidenceResponse(
                evidence.observationId(),
                evidence.submittedAtUtc(),
                evidence.payload(),
                evidence.maintenanceAffected(),
                evidence.matchedWindowId(),
                evidence.matchedMaintenanceVersion());
    }
}
