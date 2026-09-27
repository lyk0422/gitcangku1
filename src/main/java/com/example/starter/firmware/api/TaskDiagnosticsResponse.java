package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务诊断视图（只读，不改变状态）：当前任务、全部尝试代次（按代次升序）、
 * 各代次的分片接收证据（按序号升序）。
 */
public record TaskDiagnosticsResponse(TaskView task, List<AttemptView> attempts,
                                      List<ShardReceiptView> shardReceipts) {
}
