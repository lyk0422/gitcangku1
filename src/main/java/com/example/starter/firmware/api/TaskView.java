package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;

/**
 * 投放任务视图。租约字段为 null 表示从未领取；终结后冻结的实际代理与令牌随 result* 字段返回。
 */
public record TaskView(long taskId, long releaseId, String deviceId, String status,
                       String fromVersion, String toVersion,
                       String leaseAgentId, Integer leaseToken, String leaseExpiresAtUtc,
                       String resultAgentId, Integer resultToken) {

    public static TaskView of(RolloutTask task, ReleaseOrder order) {
        return new TaskView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                order.fromVersion(), order.toVersion(),
                task.leaseAgentId(), task.leaseToken(),
                task.leaseExpiresAt() == null ? null : task.leaseExpiresAt().toString(),
                task.resultAgentId(), task.resultToken());
    }
}
