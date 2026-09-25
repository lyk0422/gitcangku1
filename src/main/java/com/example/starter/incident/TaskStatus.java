package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → IN_PROGRESS → DONE/CANCELLED，或 OPEN → DONE/CANCELLED。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转；开始仅适用于 OPEN。
 */
public enum TaskStatus {

    /** 待处理（未开始）。 */
    OPEN,

    /** 处理中（已开始，未达终态）。 */
    IN_PROGRESS,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
