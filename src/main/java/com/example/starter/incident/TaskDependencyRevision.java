package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务依赖列表不可变修订记录，对应 incident_task_dependency_revisions 表。
 * 仅依赖整体替换成功时追加；revisionNo 与替换后版本一致，任务内从 1 单调递增。
 * dependenciesJson 为提交时排序后的依赖事件业务键 JSON 数组快照。时间均为 UTC。
 */
public record TaskDependencyRevision(
        long id,
        long taskId,
        int revisionNo,
        int beforeVersion,
        int afterVersion,
        String dependenciesJson,
        String actor,
        Instant occurredAt,
        Instant createdAt) {
}
