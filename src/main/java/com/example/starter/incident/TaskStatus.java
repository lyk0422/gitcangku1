package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → IN_PROGRESS → DONE；OPEN/IN_PROGRESS → CANCELLED；
 * OPEN/IN_PROGRESS → AUTHORIZATION_EXPIRED（资源授权到期，续期后恢复原状态）。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 执行中（已开始，可继续执行或完成）。 */
    IN_PROGRESS,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED，且资源授权未到期）。 */
    DONE,

    /** 已取消。 */
    CANCELLED,

    /** 资源授权已到期：禁止完成或再次分配，可由不同于原分配人的授权角色续期恢复。 */
    AUTHORIZATION_EXPIRED;
}
