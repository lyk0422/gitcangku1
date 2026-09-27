package com.example.starter.incident;

/**
 * 提案所需角色实体，对应 decision_required_roles 表。
 * 登记时写入的角色集合快照；只有持有其中角色的投票才被接受。
 * 提案历史中的角色集合在登记后不可修改。
 */
public record DecisionRole(
        long id,
        long proposalId,
        String roleCode) {
}
