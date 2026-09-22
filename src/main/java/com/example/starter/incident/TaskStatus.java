package com.example.starter.incident;

/**
 * 分组处置任务状态：OPEN → DONE 或 OPEN → CANCELLED。
 * DONE 与 CANCELLED 均为终态，不允许回退。
 */
public enum TaskStatus {

    /** 待办，可完成或取消。 */
    OPEN,

    /** 已完成，终态。 */
    DONE,

    /** 已取消，终态。 */
    CANCELLED
}
