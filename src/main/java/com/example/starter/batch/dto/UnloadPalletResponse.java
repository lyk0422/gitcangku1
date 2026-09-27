package com.example.starter.batch.dto;

import com.example.starter.batch.PalletStatus;

import java.time.Instant;
import java.util.List;

/**
 * 解除装载响应；released 为本次实际释放的批次键（按请求顺序），releasedSlotCount 为实际释放分隔位数量，
 * requestedCount 为要求解除数量，remainingCount 为托盘剩余 ACTIVE 装载数量。
 */
public record UnloadPalletResponse(
        String palletKey,
        PalletStatus palletStatus,
        List<String> released,
        int requestedCount,
        int releasedSlotCount,
        int remainingCount,
        Instant unloadedAt
) {
}
