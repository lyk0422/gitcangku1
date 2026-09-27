package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseShard;
import com.example.starter.firmware.domain.RolloutTask;

import java.util.List;

/**
 * 投放任务视图。历史字段（taskId/releaseId/deviceId/status/fromVersion/toVersion）顺序保持稳定，
 * 完整性字段追加在后；shardDigests 为当前代次拉取时固化的分片清单（按序号升序），遗留发布单为空列表。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       int attemptNo, int requiredShards, int receivedShards,
                       Integer missingShards, String failureReason, String packageDigest,
                       String attemptFinalizedAtUtc, List<String> shardDigests) {

    public static TaskView of(RolloutTask task, ReleaseOrder order, List<ReleaseShard> shards) {
        Integer missing = task.requiredShards() == 0 ? null
                : Math.max(0, task.requiredShards() - task.receivedShards());
        List<String> digests = shards == null ? List.of()
                : shards.stream().map(ReleaseShard::shardDigest).toList();
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(), task.attemptNo(), task.requiredShards(),
                task.receivedShards(), missing, task.failureReason(), task.packageDigest(),
                task.attemptFinalizedAtUtc(), digests);
    }
}
