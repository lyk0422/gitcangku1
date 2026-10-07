package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

import com.example.starter.incident.dto.Responses.ScheduleConstraintView;
import com.example.starter.incident.dto.Responses.ScheduledTaskView;

/**
 * 整案排程提案实体，对应 incident_schedules 表。
 * (incidentId, scheduleVersion) 唯一，版本事件内从 1 递增；
 * snapshot 为提交时冻结的完整提案 JSON（任务状态、事件版本及全部有向时间约束）；
 * 提案不实际执行任务，也不替换原阻塞事件规则。时间均为 UTC。
 */
public record IncidentSchedule(
        long id,
        long incidentId,
        int scheduleVersion,
        ScheduleStatus status,
        Instant baseInstant,
        String snapshot,
        String createdBy,
        Instant createdAt) {

    /**
     * 冻结快照内容：提交时的事件版本、任务状态与完整约束。
     * 任务/约束条目与查询响应视图同构，便于直接组装 ScheduleView。
     */
    public record Snapshot(long stateVersion, List<ScheduledTaskView> tasks,
                           List<ScheduleConstraintView> constraints) {
    }
}
