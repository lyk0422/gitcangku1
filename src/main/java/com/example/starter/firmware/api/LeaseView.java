package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskLease;

/**
 * 执行租约视图：token 从 1 递增；expiresAtUtc 为 UTC 到期时刻（左闭右开）。
 * terminal=true 表示任务已被一次终结，返回的是首次终结时冻结的租约快照，不代表其仍有效。
 */
public record LeaseView(long taskId, int token, String agentId, String expiresAtUtc, boolean terminal) {

    public static LeaseView of(long taskId, TaskLease lease) {
        return new LeaseView(taskId, lease.currentToken(), lease.currentAgentId(),
                lease.expiresAtUtc(), lease.terminal());
    }
}
