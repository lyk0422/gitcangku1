package com.example.starter.domain;

import java.time.Instant;

/**
 * 制品坐标的来源证明。
 *
 * <p>证明按提交顺序裁决；撤销仅影响未发布锁定图的后续解析与发布，
 * 已发布快照固化所用证明版本，不被倒改。同坐标的新版本必须重新证明。
 *
 * @param id              证明记录 ID（也是证明版本的提交顺序）
 * @param name            制品名称（坐标）
 * @param version         制品版本号
 * @param sourceRepository 来源仓标识
 * @param buildDigest     构建摘要
 * @param attestationLevel 证明等级
 * @param operator        提交操作者
 * @param revoked         是否已撤销
 * @param createdAt       提交时间，UTC
 */
public record ProvenanceAttestation(
        long id,
        String name,
        int version,
        String sourceRepository,
        String buildDigest,
        int attestationLevel,
        String operator,
        boolean revoked,
        Instant createdAt) {
}
