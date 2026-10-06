package com.example.starter.incident;

import java.time.Instant;

/**
 * 互斥处置分支组实体，对应 incident_task_branch_groups 表。
 * (incidentId, groupKey) 唯一；版本初始 1，选择成功加一，用于乐观并发裁决。
 * chosenTaskId/chosenBy/chosenAt 仅 CHOSEN 有值，否则为空。时间均为 UTC。
 */
public record IncidentTaskBranchGroup(
        long id,
        long incidentId,
        String groupKey,
        BranchGroupStatus status,
        long version,
        Long chosenTaskId,
        String createdBy,
        Instant createdAt,
        String chosenBy,
        Instant chosenAt,
        Instant updatedAt) {
}
