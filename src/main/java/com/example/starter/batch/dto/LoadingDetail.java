package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 托盘装载明细。
 * palletKey 为所属托盘；slotNo 分隔位编号（从 1 开始）；dividerVersion 为该装载达成时的隔板版本快照；
 * loadedAt/unloadedAt 为 UTC instant，unloadedAt 为 null 表示当前仍在托盘上（ACTIVE）。
 */
public record LoadingDetail(
        String palletKey,
        String batchKey,
        String segregationCategory,
        int slotNo,
        int dividerVersion,
        int seq,
        Instant loadedAt,
        Instant unloadedAt
) {
}
