package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务执行日志实体，对应 task_execution_logs 表。
 * 授权到期后日志保留，不改写；entryType 为 START 或 PROGRESS。
 */
public record TaskExecutionLog(
        long id,
        long taskId,
        String entryType,
        String note,
        String actor,
        Instant occurredAt) {
}
