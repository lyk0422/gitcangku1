package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 导致某批次不可用的召回祖先信息；后代自身不被标记为 RECALLED，仅通过该列表说明拦截来源。
 */
public record RecalledAncestorResponse(
        String batchKey,
        String reason,
        Instant recalledAt
) {
}
