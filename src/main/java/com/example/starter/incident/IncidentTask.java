package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
 * blockedZoneId/blockedSnapshot 仅 EVACUATION_BLOCKED 有值，区域结束恢复 OPEN 时清空。
 * workGrid 为任务作业网格（简化网格标识），创建时确定。
 * 时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        String workGrid,
        String createdBy,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        Long blockedZoneId,
        String blockedSnapshot,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；阻塞事件集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title, String workGrid) {
        return this.groupCode.equals(groupCode) && this.title.equals(title)
                && this.workGrid.equals(workGrid);
    }
}
