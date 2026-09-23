package com.example.starter.incident;

/**
 * 投票名册席位，对应 graph_proposal_roster 表。
 * 创建提案时冻结：每个受影响事件一个 COMMANDER 席位（当时指挥官），
 * 外加一个 SAFETY_REVIEWER 席位（incidentId 固定为 0）。后续指挥交接不改写。
 */
public record GraphRosterSeat(
        long proposalId,
        RosterRole role,
        long incidentId,
        String person) {

    /** 安全审核员席位的占位事件 id（不属于任何事件）。 */
    public static final long REVIEWER_INCIDENT_ID = 0L;
}
