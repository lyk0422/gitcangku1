package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测版本证据固化引用：对应 observation_evidence_ref 表一行。
 * 观测新版本生成时逐附件固化当时最新有效（ACTIVE）脱敏版本号；后续脱敏、撤销均不追溯回写。
 *
 * @param observationId    观测记录唯一标识
 * @param observationVersion 固化时的观测版本号
 * @param attachmentId     该版本快照引用的附件标识
 * @param redactionVersion 固化时该附件最新有效脱敏版本号；当时无有效脱敏为 null，表示快照公开原始摘要
 * @param frozenAtUtc      证据固化时刻（UTC）
 */
public record EvidenceRefRecord(
        String observationId,
        int observationVersion,
        String attachmentId,
        Integer redactionVersion,
        Instant frozenAtUtc) {
}
