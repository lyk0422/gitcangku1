package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 决策提案实体，对应 incident_decisions 表。
 * (incidentId, proposalKey) 唯一，同键同内容幂等，同键不同内容冲突。
 * taskKey/requiredRoles/quorum/expiresAt 登记后固化；decidedTaskVersion 仅 EFFECTIVE 有值，
 * decidedAt 为生效或到期判定的 UTC 时刻（OPEN 为空）。时间均为 UTC。
 */
public record IncidentDecision(
        long id,
        long incidentId,
        String proposalKey,
        long taskId,
        String taskKey,
        DecisionAction taskAction,
        int currentTaskVersion,
        List<String> requiredRoles,
        int quorum,
        DecisionStatus status,
        Integer decidedTaskVersion,
        Instant decidedAt,
        Instant expiresAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {
}
