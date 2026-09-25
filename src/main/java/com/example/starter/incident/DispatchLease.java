package com.example.starter.incident;

import java.time.Instant;

/**
 * 批量派工租约实体，对应 task_dispatch_leases 表。
 * 每任务至多一条租约（uk_dispatch_lease_task）；consumedAt 为空表示尚未开始，
 * 任务开始进入 IN_PROGRESS 时写入消费时刻。时间均为 UTC。
 */
public record DispatchLease(
        long id,
        long taskId,
        String dispatchedBy,
        String commandKey,
        Instant dispatchedAt,
        Instant consumedAt) {
}
