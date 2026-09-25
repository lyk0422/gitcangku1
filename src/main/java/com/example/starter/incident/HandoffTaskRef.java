package com.example.starter.incident;

import java.time.Instant;

/**
 * 交接资源-目标任务引用实体，对应 handoff_task_refs 表。
 * (itemId, taskId) 唯一；任务终态或交接结算时解除（删除行）。
 * 时间均为 UTC。
 */
public record HandoffTaskRef(
        long id,
        long handoffId,
        long itemId,
        long taskId,
        Instant createdAt) {
}
