package com.example.starter.incident;

/**
 * 交接结算触发原因（写入不可变 handoff_settlements，不再变更）：
 * TARGET_CLOSED 目标关闭时解除未开始任务并归还；LEASE_EXPIRED 租约到期显式结算；
 * TASK_DONE/TASK_CANCELLED 已开始任务到达终态后自动归还。
 */
public enum SettlementReason {

    /** 目标事件关闭：未开始任务解绑，资源归还来源。 */
    TARGET_CLOSED,

    /** 租约到期：未开始任务解绑，资源归还来源；已开始任务继续占用。 */
    LEASE_EXPIRED,

    /** 借用资源的已开始任务完成，任务终态自动归还。 */
    TASK_DONE,

    /** 借用资源的已开始任务取消，任务终态自动归还。 */
    TASK_CANCELLED;
}
