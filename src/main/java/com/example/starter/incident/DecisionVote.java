package com.example.starter.incident;

import java.time.Instant;

/**
 * 决策投票实体，对应 incident_decision_votes 表。
 * (decisionId, role, taskVersion) 唯一：同一角色对同一提案的同一任务版本仅一票。
 * role/ballot/taskVersion/castAt 投票时固化，不被后续操作覆盖；
 * 仅 status 随生命周期流转（CAST→WITHDRAWN/STALE），updatedAt 记录状态变更时刻。
 * 时间均为 UTC。
 */
public record DecisionVote(
        long id,
        long decisionId,
        String role,
        VoteBallot ballot,
        int taskVersion,
        VoteStatus status,
        String actor,
        Instant castAt,
        Instant updatedAt) {
}
