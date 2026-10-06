package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务资源租约实体，对应 task_resource_assignments 表。
 * 授权有效期为左闭右开区间 [assignedAt, expiresAt)，UTC 口径；
 * releasedBy/releasedAt 仅 RELEASED 有值，否则为空。到期为派生状态，不落库。
 */
public record ResourceAssignment(
        long id,
        long taskId,
        long incidentId,
        String resourceKey,
        int authorizationVersion,
        String assignedBy,
        Instant assignedAt,
        Instant expiresAt,
        AssignmentStatus status,
        String releasedBy,
        Instant releasedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 按给定时刻判断授权是否已到期（左闭右开：当前时刻 >= expiresAt 即到期）。
     */
    public boolean expiredAt(Instant now) {
        return status == AssignmentStatus.ACTIVE && !now.isBefore(expiresAt);
    }
}
