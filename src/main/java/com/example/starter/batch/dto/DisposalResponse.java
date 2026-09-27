package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;

/**
 * 隔离处置响应：已装运托盘因类别错误进入 DISPOSAL，装载记录保留。
 */
public record DisposalResponse(
        String palletKey,
        PalletStatus status,
        String reason,
        Instant createdAt
) {
}
