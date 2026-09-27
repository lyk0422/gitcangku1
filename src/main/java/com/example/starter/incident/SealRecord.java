package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存包记录明细实体，对应 incident_log_seal_records 表。
 * recordKey 为原记录标识（指令 actionKey / 交接单 id / 任务 taskKey）；
 * recordVersion 为固化时该记录的业务版本（一次性记录为 1，交接 ACCEPTED、
 * 任务 DONE/CANCELLED 为 2）；occurredAt 为记录发生 UTC 时刻；
 * seq 为包内按 (occurredAt, recordKey) 升序的序号；payload 为固化快照 JSON，不可变。
 */
public record SealRecord(
        long id,
        long sealId,
        SealRecordType recordType,
        String recordKey,
        int recordVersion,
        Instant occurredAt,
        int seq,
        String payload) {
}
