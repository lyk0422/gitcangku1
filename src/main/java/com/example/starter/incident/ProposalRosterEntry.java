package com.example.starter.incident;

import java.time.Instant;

/**
 * 提案投票名册席位实体，对应 proposal_roster_entries 表。
 * 创建提案时冻结：每个受影响事件当时的指挥官一个 COMMANDER 席位（incidentId 绑定事件），
 * 外加一个 incidentId 为空的 SAFETY_REVIEWER 席位。
 * 名册不可变：后续指挥交接不新增、不改写已提交提案的名册。
 * 同一人员可占用多行席位（多事件指挥官或兼任审核员），投票仍按人员只计一票。
 */
public record ProposalRosterEntry(
        long id,
        long proposalId,
        Long incidentId,
        String personId,
        RosterRole role,
        Instant createdAt) {
}
