package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件决策提案实体，对应 decision_proposals 表。
 * (incidentId, proposalKey) 唯一，同键同内容幂等，同键不同内容冲突。
 * taskVersion 为登记时固化的任务版本；decidedAt/decidedTaskVersion 仅
 * EFFECTIVE/EXPIRED 有值，裁决后不改写。时间均为 UTC。
 */
public record DecisionProposal(
        long id,
        long incidentId,
        String proposalKey,
        long taskId,
        int taskVersion,
        String proposedTitle,
        int quorum,
        Instant expiresAt,
        ProposalStatus status,
        String createdBy,
        Instant createdAt,
        Instant decidedAt,
        Integer decidedTaskVersion) {
}
