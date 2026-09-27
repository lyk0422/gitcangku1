package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 固化的合并/解决证据引用响应：字段与 null 语义稳定，后续脱敏不追溯改写。
 *
 * @param observationId      观测记录唯一标识
 * @param observationVersion 固化时观测版本号
 * @param attachmentId       证据引用的附件唯一标识
 * @param refType            引用类型：ORIGINAL / REDACTION
 * @param redactionId        固化脱敏版本标识；ORIGINAL 时不返回
 * @param redactionVersion   固化脱敏版本号；ORIGINAL 时不返回
 * @param ruleVersion        固化脱敏版本所用规则版本；ORIGINAL 时不返回
 * @param fixedAtUtc         证据固化时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvidenceResponse(
        String observationId,
        int observationVersion,
        String attachmentId,
        String refType,
        String redactionId,
        Integer redactionVersion,
        String ruleVersion,
        Instant fixedAtUtc) {

    public static EvidenceResponse of(EvidenceRecord record) {
        return new EvidenceResponse(record.observationId(), record.observationVersion(), record.attachmentId(),
                record.original() ? "ORIGINAL" : "REDACTION", record.redactionId(),
                record.redactionVersion(), record.ruleVersion(), record.fixedAtUtc());
    }
}
