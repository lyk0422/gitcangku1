package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ShardReceipt;

/**
 * 分片接收证据视图。所有字段固化于接收时刻，后续操作不改写。
 */
public record ShardReceiptView(long taskId, int attemptNo, long releaseId, int releaseVersion,
                               int shardNo, String shardDigest, String receivedAtUtc, String requestId) {

    public static ShardReceiptView of(ShardReceipt receipt) {
        return new ShardReceiptView(receipt.taskId(), receipt.attemptNo(), receipt.releaseId(),
                receipt.releaseVersion(), receipt.shardNo(), receipt.shardDigest(),
                receipt.receivedAtUtc(), receipt.requestId());
    }
}
