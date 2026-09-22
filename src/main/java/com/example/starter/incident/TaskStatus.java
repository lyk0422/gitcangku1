package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → DONE 或 OPEN → CANCELLED。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转；取消仅适用于 OPEN。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
