package com.example.starter.incident;

import java.time.Instant;

/**
 * 决策提案投票记录实体，对应 decision_proposal_votes 表。
 * role/vote/taskVersion/castAt 落库后不可变（固化当时业务证据）；
 * 仅 status 与 updatedAt 随撤回（WITHDRAWN）或任务修订（STALE）更新。
 * 时间均为 UTC。
 */
public record ProposalVote(
        long id,
        long proposalId,
        String role,
        VoteChoice vote,
        int taskVersion,
        VoteStatus status,
        Instant castAt,
        Instant updatedAt) {
}
