package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 合批响应。parentCount 为实际合并父批数（实际值），minParentCount 为要求的最少父批数（要求值）；
 * mergedAt 为 UTC instant。
 */
public record MergeResponse(
        String targetBatchKey,
        BatchStatus targetStatus,
        int parentCount,
        int minParentCount,
        List<String> mergedParents,
        String declarationKey,
        int declarationVersion,
        String matchedPrefix,
        Instant mergedAt
) {
}