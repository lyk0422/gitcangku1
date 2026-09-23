package com.example.starter.incident;

import java.time.Instant;

/**
 * 联合指挥交接单实体，对应 incident_handovers 表。
 * handoverKey 全局唯一；closureJson 为发起时闭包事件键升序列表 JSON，不可变；
 * snapshotJson/handoverVersion/acceptedAt 仅 ACCEPTED 有值，PENDING 为空；
 * snapshotJson 为接受时保存的不可变闭包快照（与切换时状态一致）。
 * 时间均为 UTC。
 */
public record IncidentHandover(
        long id,
        String handoverKey,
        String fromCommander,
        String toCommander,
        HandoverStatus status,
        int incidentCount,
        String closureJson,
        String snapshotJson,
        String handoverVersion,
        Instant createdAt,
        Instant acceptedAt) {
}
