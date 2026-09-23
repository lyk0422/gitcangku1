package com.example.starter.incident;

/**
 * 提案边集条目，对应 graph_proposal_edges 表。
 * 以事件 id 存储；(proposalId, operation, fromIncidentId, toIncidentId) 唯一，
 * 结构化去重后换序等价。
 */
public record GraphProposalEdge(
        long proposalId,
        EdgeOperation operation,
        long fromIncidentId,
        long toIncidentId) {
}
