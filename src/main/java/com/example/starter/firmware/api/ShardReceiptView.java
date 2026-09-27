package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ShardReceipt;

/**
 * 分片接收证据视图：expectedDigest 为 null 表示越界序号（登记清单中不存在）。
 */
public record ShardReceiptView(int attemptNo, int shardIndex, String expectedDigest,
                               String receivedDigest, boolean digestMatched, String receivedAtUtc) {

    public static ShardReceiptView of(ShardReceipt receipt) {
        return new ShardReceiptView(receipt.attemptNo(), receipt.shardIndex(), receipt.expectedDigest(),
                receipt.receivedDigest(), receipt.digestMatched(), receipt.receivedAtUtc());
    }
}
