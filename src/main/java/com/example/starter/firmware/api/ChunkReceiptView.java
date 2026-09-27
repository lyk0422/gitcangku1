package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ChunkReceipt;

/**
 * 分片接收证据视图（只读）。
 */
public record ChunkReceiptView(long receiptId, int attemptNo, int chunkIndex, String digest,
                               String receivedAtUtc) {

    public static ChunkReceiptView of(ChunkReceipt receipt) {
        return new ChunkReceiptView(receipt.id(), receipt.attemptNo(), receipt.chunkIndex(),
                receipt.digest(), receipt.receivedAtUtc());
    }
}
