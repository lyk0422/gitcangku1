package com.example.starter.incident;

import java.time.Instant;

/**
 * 互斥处置分支组实体，对应 incident_branch_groups 表。
 * (incidentId, groupKey) 唯一，同键同成员集合幂等，同键不同集合冲突；
 * version 初始 1，选择成功后加一；chosenTaskId/chosenBy/chosenAt 仅 CHOSEN 有值。
 * 时间均为 UTC。
 */
public record IncidentBranchGroup(
        long id,
        long incidentId,
        String groupKey,
        BranchGroupStatus status,
        long version,
        Long chosenTaskId,
        String chosenBy,
        Instant chosenAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
