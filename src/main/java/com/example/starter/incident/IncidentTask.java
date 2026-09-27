package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
 * version 自 1 起，每次任务修订（标题/分组变更）加 1；决策提案按版本计票，
 * 版本变化后旧票转 STALE 不计入新版本。时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        int version,
        String createdBy,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；阻塞事件集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title) {
        return this.groupCode.equals(groupCode) && this.title.equals(title);
    }
}
