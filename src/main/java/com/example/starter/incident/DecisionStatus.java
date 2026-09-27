package com.example.starter.incident;

/**
 * 决策提案状态机：OPEN → EFFECTIVE 或 OPEN → EXPIRED。
 * EFFECTIVE（到期前达法定人数并原子写入任务变更）与 EXPIRED（到期未达法定人数）均为终态。
 */
public enum DecisionStatus {

    /** 投票中。 */
    OPEN,

    /** 已生效：到期前 SUPPORT 票达法定人数，任务变更已原子写入。 */
    EFFECTIVE,

    /** 已到期：到达 UTC 到期时刻仍未达法定人数。 */
    EXPIRED;
}
