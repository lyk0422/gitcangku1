package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存记录更正附录实体，对应 incident_seal_appendices 表。
 * 引用原包版本与原记录标识，保留原值（封存快照 JSON）、新值、理由与操作人；
 * 只增不改，已关闭事件仍可追加；不得用于修改原包内容。时间均为 UTC。
 */
public record IncidentSealAppendix(
        long id,
        long incidentId,
        long packageId,
        int packageVersion,
        String recordKey,
        String oldValue,
        String newValue,
        String reason,
        String actor,
        Instant createdAt) {
}
