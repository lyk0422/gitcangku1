package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;

/**
 * 托盘概要响应。dividerVersion 随每次隔板修改递增；createdAt 为 UTC。
 */
public record PalletResponse(
        String palletKey,
        int dividerCount,
        int dividerVersion,
        PalletStatus status,
        Instant createdAt
) {
}
