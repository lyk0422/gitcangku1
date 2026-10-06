package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → DONE 或 OPEN → CANCELLED；OPEN ↔ AUTHORIZATION_EXPIRED。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转；取消仅适用于 OPEN/AUTHORIZATION_EXPIRED。
 * AUTHORIZATION_EXPIRED 由命令路径按时钟懒判定落库（读取只计算不写回），
 * 禁止完成或再次分配，仅可通过续期恢复为 OPEN。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED 且授权未到期）。 */
    DONE,

    /** 已取消。 */
    CANCELLED,

    /** 授权已到期：禁止完成或再次分配，已执行日志保留，续期后恢复 OPEN。 */
    AUTHORIZATION_EXPIRED;
}
