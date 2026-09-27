package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存包记录条目实体，对应 incident_seal_entries 表。
 * 包内按 (occurredAt, recordKey, recordType) 排序编 seqNo；payload 为封存时刻的
 * 完整字段快照 JSON，只增不改。recordVersion：ACTION/TRANSFER/STATUS_CHANGE 恒为 1；
 * TASK 为截至本包被收录的次数（每次封存生成新快照版本）。时间均为 UTC。
 */
public record IncidentSealEntry(
        long id,
        long packageId,
        int seqNo,
        String recordType,
        String recordKey,
        Instant occurredAt,
        int recordVersion,
        String payload) {
}
