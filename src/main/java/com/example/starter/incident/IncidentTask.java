package com.example.starter.incident;

import java.time.Instant;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
 * version 创建时为 1，启用双人监护后递增为 2。
 * supervisionExecutor/supervisionGuardian 仅启用双人监护后有值，且两者必须不同。
 * doneXxxIssuedAt/doneXxxExpiresAt 为完成时冻结的双方凭证签发与到期 UTC 时刻，
 * 仅双人监护任务 DONE 有值。时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        String createdBy,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        int version,
        String supervisionExecutor,
        String supervisionGuardian,
        Instant doneExecutorIssuedAt,
        Instant doneExecutorExpiresAt,
        Instant doneGuardianIssuedAt,
        Instant doneGuardianExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；阻塞事件集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title) {
        return this.groupCode.equals(groupCode) && this.title.equals(title);
    }

    /**
     * 是否已启用双人监护（固定执行人与监护人）。
     */
    public boolean supervisionEnabled() {
        return supervisionExecutor != null;
    }
}
