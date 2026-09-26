package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务完整性明细：当前状态、各代次接收证据与判定事件。只读，查询不改变状态。
 */
public record TaskIntegrityView(long taskId, long releaseId, String deviceId, int attemptNo,
                                String status, String aggregateDigest,
                                List<ShardReceiptView> receipts, List<IntegrityEventView> events) {
}
