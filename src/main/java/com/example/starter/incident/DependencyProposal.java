package com.example.starter.incident;

import java.time.Instant;

/**
 * 依赖图变更提案实体，对应 dependency_change_proposals 表。
 * proposalKey 全局唯一；expectedGraphVersion 为创建时声明的基线图版本，
 * 激活时必须仍与当前图版本一致，否则 409 不改图。
 * changesJson 为规范化（去重、换序等价）后增删边集合 JSON；
 * beforeEdgesJson/afterEdgesJson 仅激活成功后写入前后边集快照（稳定排序）。
 * activatedGraphVersion 为激活后生成的唯一新版本号，PENDING/REJECTED 时为空。
 * 时间均为 UTC。
 */
public record DependencyProposal(
        long id,
        String proposalKey,
        long expectedGraphVersion,
        String businessNote,
        String safetyReviewer,
        String createdBy,
        String changesJson,
        ProposalStatus status,
        Long activatedGraphVersion,
        String beforeEdgesJson,
        String afterEdgesJson,
        Instant createdAt,
        Instant activatedAt) {
}
