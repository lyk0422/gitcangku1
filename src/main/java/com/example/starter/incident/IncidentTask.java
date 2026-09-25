package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
 * plannedCompleteAt 为计划完成 UTC 时刻，仅声明必需资质的高危任务非空；
 * preRiskStatus 记录进入 CREDENTIAL_RISK 前的状态（OPEN/IN_PROGRESS），替换合格租约后恢复。
 * 必需资质集合存放于 task_required_credentials 表，按代码排序，换序视为同参。
 * 时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        Instant plannedCompleteAt,
        TaskStatus preRiskStatus,
        String createdBy,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；
     * 阻塞事件集合与必需资质集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title, Instant plannedCompleteAt) {
        return this.groupCode.equals(groupCode) && this.title.equals(title)
                && java.util.Objects.equals(this.plannedCompleteAt, plannedCompleteAt);
    }
}
