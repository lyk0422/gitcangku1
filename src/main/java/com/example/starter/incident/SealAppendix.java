package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存包更正附录实体，对应 incident_log_appendices 表。
 * 引用原包版本与原记录标识，保留原值、新值、理由与操作人；
 * 只追加不修改原包，已关闭事件允许追加。oldValue 为空表示原字段为 null。
 * 时间均为 UTC。
 */
public record SealAppendix(
        long id,
        long incidentId,
        long sealId,
        int sealVersion,
        String recordKey,
        String fieldName,
        String oldValue,
        String newValue,
        String reason,
        String operator,
        Instant createdAt) {
}
