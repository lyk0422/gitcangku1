package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次合格证明细：当前绑定与放行快照。未绑定 binding 为 null；未放行 release 为 null。
 */
public record CertificateDetailResponse(
        String batchKey,
        BindingInfo binding,
        ReleaseInfo release
) {

    /**
     * 绑定信息：声明版本、物料、匹配前缀、操作人与绑定时点。
     */
    public record BindingInfo(
            String declarationKey,
            int version,
            String materialCode,
            String matchedPrefix,
            String actorId,
            Instant boundAt
    ) {
    }

    /**
     * 放行快照：放行时点固化的声明版本、匹配前缀与时点；声明撤回不改写。
     */
    public record ReleaseInfo(
            String declarationKey,
            int version,
            String matchedPrefix,
            Instant releasedAt
    ) {
    }
}
