package com.example.starter.observation;

import java.time.Instant;

/**
 * 合并/人工冲突解决时固化的附件证据引用：对应 observation_evidence 表一行，永不改写。
 *
 * @param observationId      观测记录唯一标识
 * @param observationVersion 固化证据时观测记录版本号
 * @param attachmentId       证据引用的附件唯一标识
 * @param original           是否引用原始摘要：true=ORIGINAL；false=REDACTION 某脱敏版本
 * @param redactionId        固化的脱敏版本标识；ORIGINAL 时为 null
 * @param redactionVersion   固化的脱敏版本号；ORIGINAL 时为 null
 * @param ruleVersion        固化脱敏版本所用规则版本；ORIGINAL 时为 null
 * @param fixedAtUtc         证据固化时刻（UTC）
 */
public record EvidenceRecord(
        String observationId,
        int observationVersion,
        String attachmentId,
        boolean original,
        String redactionId,
        Integer redactionVersion,
        String ruleVersion,
        Instant fixedAtUtc) {
}
