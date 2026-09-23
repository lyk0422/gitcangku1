package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务版本修订历史实体，对应 incident_task_revisions 表（不可变，仅追加）。
 * operation：REPLACE 依赖替换 / COMPLETE 完成 / CANCEL 取消；
 * fromVersion/toVersion 为变更前后任务版本（toVersion = fromVersion + 1）；
 * blockerKeys 为变更生效后排序后的依赖事件键 JSON 数组（空数组表示无依赖）。
 * 时间均为 UTC。
 */
public record TaskRevision(
        long id,
        long taskId,
        String operation,
        String actor,
        int fromVersion,
        int toVersion,
        String blockerKeys,
        Instant occurredAt) {
}
