package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → IN_PROGRESS → DONE，OPEN/IN_PROGRESS → CANCELLED，
 * OPEN/IN_PROGRESS → AUTHORIZATION_EXPIRED（资源授权到期，命令或访问时按时钟判定）。
 * DONE、CANCELLED 与 AUTHORIZATION_EXPIRED 均为终态，不允许任何后续流转；
 * AUTHORIZATION_EXPIRED 任务禁止完成或再次分配资源，已执行日志保留。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 执行中（已在有效资源授权下开始执行）。 */
    IN_PROGRESS,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED，且授权未到期）。 */
    DONE,

    /** 已取消。 */
    CANCELLED,

    /** 资源授权已到期（终态）：禁止完成或再次分配，已执行日志保留。 */
    AUTHORIZATION_EXPIRED;
}
