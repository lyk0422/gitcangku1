package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务分片核验明细（只读，不改变状态）：当前尝试代次下每个分片的期望摘要、实际摘要与接收时刻，
 * 以及实际接收数、要求总数与缺失数差额。
 */
public record IntegrityDetailResponse(long taskId, long releaseId, String deviceId, String status,
                                      int attemptNo, int requiredChunks, int receivedChunks,
                                      int missingChunkCount, String packageDigest,
                                      List<ChunkStatusView> chunks) {

    /**
     * 单个分片的核验明细。
     */
    public record ChunkStatusView(int chunkIndex, String expectedDigest, String receivedDigest,
                                  String receivedAtUtc) {
    }
}
