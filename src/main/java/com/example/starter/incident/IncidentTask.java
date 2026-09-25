package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。priority 为任务优先级（默认 NORMAL），仅 HIGH 受外部机构门禁约束；
 * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
 * 时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskPriority priority,
        TaskStatus status,
        String createdBy,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；优先级与阻塞事件集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title) {
        return this.groupCode.equals(groupCode) && this.title.equals(title);
    }
}
