package com.example.starter.observation;

import java.time.Instant;

/**
 * 许可撤销复核快照：对应 permit_review_snapshot 表的一行，记录撤销许可时被标记
 * PERMIT_REVIEW 的观测及其当时版本，不可变、只追加。
 *
 * @param id                 快照自增主键（插入时为 null）
 * @param observationId      被标记复核的观测记录标识
 * @param permitId           被撤销的许可标识
 * @param permitVersion      被撤销许可的版本（permitVersion）
 * @param observationVersion 标记复核时观测记录的当前版本号
 * @param revokedAtUtc       许可撤销时刻（UTC）
 */
public record PermitReviewSnapshot(
        Long id,
        String observationId,
        String permitId,
        int permitVersion,
        int observationVersion,
        Instant revokedAtUtc) {
}
