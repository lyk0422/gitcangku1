package com.example.starter.batch.dto;

import com.example.starter.batch.PalletLoadStatus;

import java.time.Instant;

/**
 * 托盘装载明细。reviewMarkedAt 为 null 表示从未被标记待复核；
 * unloadedAt 为 null 表示未解除；历史字段一旦写入不随后续操作改写。
 */
public record PalletLoadEntry(
        String batchKey,
        String category,
        int compartmentNo,
        int seq,
        PalletLoadStatus status,
        boolean pendingReview,
        Instant reviewMarkedAt,
        Instant loadedAt,
        Instant unloadedAt
) {
}
