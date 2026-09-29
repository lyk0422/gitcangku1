package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 放行固化的合格证快照：第二批准落定 RELEASED 时在同一事务写入，此后不可改写。
 * 声明后续撤回不影响本快照；validFrom/validTo 为声明区间副本，按左闭右开解释。
 */
public record ReleaseCertificateSnapshotResponse(
        String batchKey,
        String materialCode,
        String declarationKey,
        int version,
        String matchedPrefix,
        Instant validFrom,
        Instant validTo,
        Instant releasedAt
) {
}
