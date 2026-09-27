package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 解除装载响应：released 为本次释放分隔位的批次明细（含解除时间），历史记录保留。
 */
public record UnloadResponse(
        String palletKey,
        List<PalletLoadEntry> released,
        Instant unloadedAt
) {
}
