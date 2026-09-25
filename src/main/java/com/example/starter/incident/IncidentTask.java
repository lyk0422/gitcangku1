package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。requiredCredentials 为高危任务必需资质代码集合（已按字典序去重），
 * 空列表表示非高危任务；集合换序视为同参。
 * startedBy/startedAt 仅 IN_PROGRESS/CREDENTIAL_RISK/DONE 有值；
 * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值；
 * preRiskStatus 仅 CREDENTIAL_RISK 有值（进入风险前的状态，替换租约后据此恢复）。
 * 时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        List<String> requiredCredentials,
        String createdBy,
        String startedBy,
        Instant startedAt,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        TaskStatus preRiskStatus,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；阻塞事件集合另行比对）。
     * 必需资质集合按字典序归一化后比对，换序视为同参。
     */
    public boolean sameContent(String groupCode, String title, List<String> requiredCredentials) {
        return this.groupCode.equals(groupCode) && this.title.equals(title)
                && this.requiredCredentials.equals(requiredCredentials);
    }
}
