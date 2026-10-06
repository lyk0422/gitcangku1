package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → STARTED → DONE，或 OPEN → DONE（无安排时），
 * 或 OPEN → CANCELLED、STARTED → CANCELLED。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转；取消仅适用于 OPEN 与 STARTED。
 * 有执行安排的任务必须经 STARTED 才能 DONE；STARTED 任务的安排不可变更。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 已开始（须存在执行安排且开始时刻落在安排区间内、阻塞已解除）。 */
    STARTED,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED；有安排的任务须先开始）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
