package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。
 * assignedResourceId/assignedHandoffId 非空表示任务占用了通过该交接借入的资源；
 * 未开始任务在目标关闭或租约到期时同事务解绑（两列置空）；已开始（STARTED）任务
 * 继续占用直至 DONE/CANCELLED 终态，终态后自动结算归还。
 * startedBy/startedAt 仅任务开始后有值；doneBy/doneAt 仅 DONE 有值，
 * cancelledBy/cancelledAt 仅 CANCELLED 有值。时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        Long assignedResourceId,
        Long assignedHandoffId,
        String startedBy,
        Instant startedAt,
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
