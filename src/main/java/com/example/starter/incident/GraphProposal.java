package com.example.starter.incident;

import java.time.Instant;

/**
 * 依赖图变更提案实体，对应 graph_proposals 表。
 * expectedGraphVersion 为提案基于的图版本，激活时须仍匹配；
 * appliedGraphVersion 仅 ACTIVATED 有值，为激活生成的新图版本。时间均为 UTC。
 */
public record GraphProposal(
        long id,
        String proposalKey,
        GraphProposalStatus status,
        String rationale,
        String proposer,
        String safetyReviewer,
        long expectedGraphVersion,
        Long appliedGraphVersion,
        Instant createdAt,
        Instant updatedAt) {
}
