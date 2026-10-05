package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务资源租约实体，对应 task_resource_assignments 表（每任务至多一条当前租约）。
 * 租约区间为 [assignedAt, expiresAt)，左闭右开，UTC 口径；当前时刻 ≥ expiresAt 即到期。
 * assignedBy 为原分配人，续期必须由其以外的授权角色提交严格更大的授权版本；
 * 历史版本与操作轨迹固化在 resource_authorization_events，本表仅保存当前状态。
 */
public record TaskResourceAssignment(
        long id,
        long taskId,
        long incidentId,
        String resourceKey,
        long authorizationVersion,
        String assignedBy,
        Instant assignedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断在指定 UTC 时刻租约是否已到期（区间右开：now ≥ expiresAt 即到期）。
     */
    public boolean expiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
