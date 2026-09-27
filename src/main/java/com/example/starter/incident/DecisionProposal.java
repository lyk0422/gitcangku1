package com.example.starter.incident;

import java.time.Instant;

/**
 * 事件处置提案实体，对应 incident_decisions 表。
 * expiresAt 为登记的 UTC 到期时刻；投票有效区间为左闭右开 [createdAt, expiresAt)。
 * quorum 为生效所需的支持票法定人数（实际支持票数达到该值才生效）；
 * baseTaskRevision 为登记时固化的任务版本，任务修订后版本变化，旧版本票全部 STALE。
 * effectiveAt/effectiveBy/effectiveTaskRevision 仅 EFFECTIVE 有值；expiredAt 仅 EXPIRED 有值。
 * 生效、到期、失效均固化任务版本、角色与时刻，后续任务修订不改写本提案历史。
 */
public record DecisionProposal(
        long id,
        long incidentId,
        String proposalKey,
        String taskKey,
        DecisionType decisionType,
        Instant expiresAt,
        int quorum,
        int baseTaskRevision,
        DecisionStatus status,
        String createdBy,
        Instant createdAt,
        Instant effectiveAt,
        String effectiveBy,
        Integer effectiveTaskRevision,
        Instant expiredAt) {
}
