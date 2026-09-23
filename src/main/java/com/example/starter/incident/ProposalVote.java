package com.example.starter.incident;

import java.time.Instant;

/**
 * 提案票决实体，对应 proposal_votes 表。
 * 按人员唯一（(proposal_id, person_id)）：同一人员兼任多少席位都只投一票，
 * 该票同时代表其承担的全部角色席位；只能首次投 YES 或 NO，不可更改。
 */
public record ProposalVote(
        long id,
        long proposalId,
        String personId,
        VoteChoice choice,
        Instant votedAt,
        Instant createdAt) {
}
