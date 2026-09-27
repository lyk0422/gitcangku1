package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务完整性历史（只读，不改变状态）：尝试代次、接收证据与判定事件均按时间/序号稳定排序，
 * 重新拉取的新代次不改写旧证据。
 */
public record IntegrityHistoryResponse(long taskId, long releaseId, String status,
                                       List<AttemptView> attempts,
                                       List<ChunkReceiptView> receipts,
                                       List<IntegrityEventView> events) {
}
