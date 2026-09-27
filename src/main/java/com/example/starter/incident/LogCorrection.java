package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存日志更正附录实体，对应 incident_log_corrections 表。
 * 已封存记录发现事实错误时只追加附录：packageVersion/recordType/recordKey 定位原包内原记录，
 * oldValue 取自原包快照（原值为 null 时存 NULL，保留 null 语义），
 * newValue 为更正值，reason 为理由，operator 为操作人，createdAt 为追加 UTC 时刻。
 * 原封存包内容不做任何更新；事件关闭后仍允许追加。
 */
public record LogCorrection(
        long id,
        long incidentId,
        int packageVersion,
        String recordType,
        String recordKey,
        String fieldName,
        String oldValue,
        String newValue,
        String reason,
        String operator,
        Instant createdAt) {
}
