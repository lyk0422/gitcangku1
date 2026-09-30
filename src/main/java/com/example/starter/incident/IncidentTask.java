package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。resourceKey 为任务执行所需资源，region 为任务执行区域（UTC 无关的区域标识），
 * 派发与执行前复检均要求凭据同类型、覆盖该区域且在 UTC 有效期内。
 * executingBy/executingAt 仅 EXECUTING 及 DONE 有值（进入执行状态时写入）；
 * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
 * credentialVersion 为最近一次成功派发绑定的凭据版本，未派发为空；BLOCKED 时保留阻断所依据的版本。
 * blockReason 仅 BLOCKED 有值（CREDENTIAL_EXPIRED/CREDENTIAL_REVOKED）。时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        String resourceKey,
        String region,
        TaskStatus status,
        String createdBy,
        String executingBy,
        Instant executingAt,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        Integer credentialVersion,
        String blockReason,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；阻塞事件集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title, String resourceKey, String region) {
        return this.groupCode.equals(groupCode)
                && this.title.equals(title)
                && this.resourceKey.equals(resourceKey)
                && this.region.equals(region);
    }
}
