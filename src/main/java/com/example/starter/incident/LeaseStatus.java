package com.example.starter.incident;

/**
 * 租约状态：ACTIVE 当前生效；REPLACED 已被合格租约替换；
 * RISK_CLOSED 资质风险在任务进入终态后关闭保留。
 */
public enum LeaseStatus {

    /** 当前生效。 */
    ACTIVE,

    /** 已被合格租约替换（历史租约）。 */
    REPLACED,

    /** 风险随任务终态关闭。 */
    RISK_CLOSED;
}
