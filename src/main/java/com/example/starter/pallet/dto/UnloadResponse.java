package com.example.starter.pallet.dto;

import java.time.Instant;
import java.util.List;

/**
 * 解除装载响应：释放的分隔位明细；装载历史保留不删除。
 */
public record UnloadResponse(
        String palletKey,
        List<ReleasedItem> released,
        Instant unloadedAt
) {
    /**
     * 被释放的在托项：批次与释放的分隔位。
     */
    public record ReleasedItem(String batchKey, int compartment) {
    }
}
