package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ChunkReceipt;

/**
 * 分片接收证据视图（历史查询，只读）。
 */
public record ReceiptView(int chunkIndex, String digest, String expectedDigest,
                          boolean matched, String receivedAtUtc) {

    public static ReceiptView of(ChunkReceipt receipt) {
        return new ReceiptView(receipt.chunkIndex(), receipt.digest(), receipt.expectedDigest(),
                receipt.matched(), receipt.receivedAtUtc());
    }
}
