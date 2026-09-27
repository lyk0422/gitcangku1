package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;

/**
 * 已装运托盘隔离处置响应；装载记录与装运快照均保留，不删除。
 */
public record QuarantinePalletResponse(
        String palletKey,
        PalletStatus palletStatus,
        String reason,
        Instant quarantinedAt
) {
}
