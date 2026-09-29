package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 批次合格证绑定明细。inheritedFrom 为 null 表示直接绑定；非空为拆分继承来源父批键。
 * boundAt 为 UTC 绑定时点；绑定后声明撤回不改写本记录。
 */
public record CertificateBindingResponse(
        String batchKey,
        String materialCode,
        String declarationKey,
        int version,
        String matchedPrefix,
        String inheritedFrom,
        String boundBy,
        Instant boundAt
) {
}
