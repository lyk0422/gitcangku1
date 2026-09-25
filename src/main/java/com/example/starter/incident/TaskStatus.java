package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → STARTED → DONE/CANCELLED，OPEN 也可直接 DONE/CANCELLED。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转。
 * STARTED 表示任务已开始：其占用的互助借用资源在租约到期或目标关闭时不解除，
 * 一直占用至任务到达终态后自动结算归还。
 */
public enum TaskStatus {

    /** 待处理，尚未开始。 */
    OPEN,

    /** 已开始，执行中。 */
    STARTED,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
