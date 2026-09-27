package com.example.starter.incident;

import java.time.Instant;

/**
 * 提案投票实体，对应 decision_votes 表。
 * taskRevision 为投票针对的任务版本；任务版本变化后同角色旧票转 STALE（staleAt 有值），
 * 不计入新版本法定人数；投票人撤回转 WITHDRAWN（withdrawnAt 有值），仅 CAST 票计入。
 * 同一提案内每个角色至多保留一张 CAST 票（uk_decision_active_role 唯一约束兜底）；
 * STALE/WITHDRAWN 历史行永久保留，其字段、排序与 null 含义不被后续操作覆盖。
 * 时间均为 UTC，精度到微秒。
 */
public record DecisionVote(
        long id,
        long proposalId,
        int taskRevision,
        String voterActor,
        String roleCode,
        VoteChoice choice,
        VoteStatus status,
        Instant votedAt,
        Instant staleAt,
        Instant withdrawnAt) {
}
