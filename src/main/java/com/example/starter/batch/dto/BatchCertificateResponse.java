package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次合格证明细查询响应：当前绑定与放行快照。
 * 未绑定时 binding 为 null；未放行时 releaseSnapshot 为 null。
 */
public record BatchCertificateResponse(
        String batchKey,
        BindingResponse binding,
        ReleaseSnapshotResponse releaseSnapshot
) {

    /**
     * 放行快照：放行时点固化的声明版本、匹配前缀与放行时间（UTC），创建后不改写。
     */
    public record ReleaseSnapshotResponse(
            String declarationKey,
            int declarationVersion,
            String matchedPrefix,
            Instant releasedAt
    ) {
    }
}