package com.example.starter.pallet.dto;

import java.time.Instant;

/**
 * 隔离处置响应：已装运托盘发现类别错误后进入 QUARANTINE_DISPOSAL。
 */
public record DisposalResponse(
        String palletKey,
        String status,
        String reason,
        String actor,
        Instant disposedAt
) {
}
