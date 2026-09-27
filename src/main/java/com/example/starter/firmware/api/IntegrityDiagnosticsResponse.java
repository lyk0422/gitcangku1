package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务完整性诊断（只读，不改变状态）：进度实际值/要求值/缺失差额、当前接收集合的聚合结果，
 * 以及事件计数与完整事件明细，供定位缺失、重复或摘要不匹配原因。
 */
public record IntegrityDiagnosticsResponse(long taskId, long releaseId, String status, int attemptNo,
                                           int requiredChunks, int receivedChunks, int missingChunkCount,
                                           String packageDigest, String aggregateDigest,
                                           int acceptedEventCount, int failureEventCount,
                                           int installableEventCount,
                                           List<IntegrityEventView> events) {
}
