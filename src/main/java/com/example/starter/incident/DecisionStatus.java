package com.example.starter.incident;

/**
 * 处置提案状态机：OPEN → EFFECTIVE 或 OPEN → EXPIRED。
 * OPEN 为投票中；到期前达法定人数生效为 EFFECTIVE；到达到期时刻仍未生效为 EXPIRED。
 * EFFECTIVE 与 EXPIRED 均为终态，不允许再投票、撤回或修订。
 */
public enum DecisionStatus {

    /** 投票中（UTC 时刻位于 [createdAt, expiresAt) 区间）。 */
    OPEN,

    /** 到期前达法定人数，已原子写入任务变更。 */
    EFFECTIVE,

    /** 到达到期时刻仍未达法定人数（含到期时刻当次投票触发的惰性到期）。 */
    EXPIRED;
}
