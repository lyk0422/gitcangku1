package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务分片完整性诊断视图（只读）。chunks 为当前代次已接收证据，按序号升序；
 * decisions 为全部代次的判定历史，按判定顺序升序，可用 fromUtc/toUtc 按左闭右开区间过滤。
 */
public record TaskIntegrityView(long taskId, long releaseId, String deviceId, String status, int attempt,
                                int requiredCount, int receivedCount, int missingCount,
                                List<ChunkEvidenceView> chunks,
                                List<IntegrityDecisionView> decisions) {

    /**
     * 分片接收证据。
     */
    public record ChunkEvidenceView(int attempt, int chunkIndex, String digest, String receivedAtUtc) {
    }

    /**
     * 完整性判定记录。reason、computedPackageDigest 的 null 语义与存储一致：
     * 成功时 reason 为 null；集合不完整时 computedPackageDigest 为 null。
     */
    public record IntegrityDecisionView(int attempt, String result, String reason,
                                        int receivedCount, int requiredCount,
                                        String computedPackageDigest, String expectedPackageDigest,
                                        String firmwareVersion, String decidedAtUtc) {
    }
}
