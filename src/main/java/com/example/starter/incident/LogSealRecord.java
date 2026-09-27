package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存包明细实体，对应 incident_log_seal_records 表。
 * seq 为包内序号，从 1 开始，按 (occurredAt, recordKey) 升序固化；
 * recordVersion 为该记录在同类型事件历史中的版本序号（从 1 计）；
 * payload 为封存时刻的记录快照 JSON，字段、时间精度与 null 语义原样保留。
 * taskId 仅任务状态/依赖边条目有值；blockerIncidentId 仅依赖边条目有值，其余为 null。
 */
public record LogSealRecord(
        long id,
        long sealId,
        int seq,
        String recordType,
        String recordKey,
        Instant occurredAt,
        int recordVersion,
        Long taskId,
        Long blockerIncidentId,
        String payload) {
}
