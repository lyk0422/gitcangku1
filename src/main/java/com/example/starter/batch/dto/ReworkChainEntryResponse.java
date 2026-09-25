package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 返工链明细条目：一次返工重投对应一条，按代次从低到高排列。
 * sourceBatchKey 为被返工的原批次（返工后 REWORKED），reworkBatchKey 为生成的新返工批次；
 * generation 为新批次代次（原批次代次加一）。createdAt 为 UTC instant。
 */
public record ReworkChainEntryResponse(
        String reworkKey,
        String sourceBatchKey,
        String reworkBatchKey,
        int generation,
        String reason,
        Instant createdAt
) {
}
