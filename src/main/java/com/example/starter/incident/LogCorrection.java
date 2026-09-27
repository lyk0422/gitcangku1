package com.example.starter.incident;

import java.time.Instant;

/**
 * 封存更正附录实体，对应 incident_log_corrections 表。
 * 仅追加：引用原封存包版本 sealVersion 与原记录标识 recordKey，
 * 保留原值 oldValue、新值 newValue、理由 reason 与操作人 correctedBy；
 * 不直接更新原包与明细。oldValue/newValue 允许为 null，表达原值或更正值为空。
 */
public record LogCorrection(
        long id,
        long incidentId,
        long sealId,
        int sealVersion,
        String recordKey,
        String fieldName,
        String oldValue,
        String newValue,
        String reason,
        String correctedBy,
        Instant createdAt) {
}
