package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → DONE、OPEN → CANCELLED 或 OPEN → AUTHORIZATION_EXPIRED。
 * DONE、CANCELLED 与 AUTHORIZATION_EXPIRED 均为终态，不允许任何后续流转；取消仅适用于 OPEN。
 * AUTHORIZATION_EXPIRED 由命令/访问时按注入时钟判定（当前时刻 ≥ 活动租约到期时刻），
 * 到期后禁止完成或再次分配，已执行日志保留。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED）。 */
    DONE,

    /** 已取消。 */
    CANCELLED,

    /** 授权已到期（资源租约到期且未续期，终态；已执行日志与租约证据保留）。 */
    AUTHORIZATION_EXPIRED;
}
