package com.example.starter.firmware.api;

import java.util.List;

/**
 * 分片接收提交响应。status 为提交后任务状态（PENDING 继续接收 / INSTALLABLE 可安装 /
 * INTEGRITY_FAILED 完整性失败）；数量字段返回实际值与要求值，missingShards 等列表
 * 为空时返回空数组而非 null；decidedAtUtc 仅当本次提交触发判定时非 null。
 */
public record ShardReceiveResponse(long taskId, int attemptNo, String status, String reason,
                                   int requiredShardCount, int receivedShardCount, int missingShardCount,
                                   List<Integer> missingShards, List<Integer> duplicateShards,
                                   List<Integer> mismatchedShards, List<Integer> outOfRangeShards,
                                   String aggregateDigest, String decidedAtUtc) {
}
