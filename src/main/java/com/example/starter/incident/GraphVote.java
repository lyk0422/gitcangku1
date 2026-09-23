package com.example.starter.incident;

import java.time.Instant;

/**
 * 票决记录，对应 graph_proposal_votes 表。
 * (proposalId, person) 唯一：每名名册成员仅首票有效；
 * 兼任多席位的人员仍只投一票，一票同时满足其全部席位。时间为 UTC。
 */
public record GraphVote(
        long proposalId,
        String person,
        VoteDecision decision,
        Instant votedAt) {
}
