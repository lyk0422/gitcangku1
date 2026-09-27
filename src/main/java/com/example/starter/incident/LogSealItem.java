package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存包条目实体，对应 incident_log_seal_items 表。
 * seqNo 从 1 开始，按记录发生 UTC 时刻升序、同刻按记录类型与记录标识升序确定；
 * recordType 取值 ACTION/TRANSFER/STATUS/TASK/DEPENDENCY；recordKey 为同刻排序标识；
 * recordVersion 为封存时该记录的修订版本（创建为 1，交接接受/任务终态为 2）；
 * fromTaskKey/toIncidentKey 仅 DEPENDENCY 依赖边有值，其余为 null；
 * payload 为记录快照 JSON，字段值与 null 语义原样固化。
 */
public record LogSealItem(
        long id,
        long sealId,
        int seqNo,
        String recordType,
        String recordKey,
        Instant occurredAt,
        int recordVersion,
        String fromTaskKey,
        String toIncidentKey,
        String payload) {
}
