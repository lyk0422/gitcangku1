package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务执行日志实体，对应 incident_task_executions 表。
 * 只追加不改写；任务转为 AUTHORIZATION_EXPIRED 后日志保留。时间为 UTC。
 */
public record TaskExecution(
        long id,
        long taskId,
        ExecutionAction action,
        String actor,
        Instant occurredAt) {
}
