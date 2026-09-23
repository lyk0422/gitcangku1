package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → STARTED → DONE/CANCELLED，或 OPEN → DONE/CANCELLED。
 * 标记 STARTED 前任务必须持有 ACTIVE 租约；DONE 与 CANCELLED 均为终态，
 * 不允许任何后续流转；取消仅适用于 OPEN/STARTED。
 */
public enum TaskStatus {

    /** 待处理（未启动）。 */
    OPEN,

    /** 已启动（启动时持有 ACTIVE 租约）。 */
    STARTED,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
