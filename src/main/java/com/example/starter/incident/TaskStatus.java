package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → IN_PROGRESS → DONE；OPEN 可直接 DONE（未显式开始的任务）；
 * OPEN → CANCELLED；OPEN ↔ EVACUATION_BLOCKED（疏散区域生效/结束驱动）；
 * IN_PROGRESS → EVACUATED（撤离登记，终态，不可完成）。
 * DONE、CANCELLED、EVACUATED 均为终态，不允许任何后续流转；取消仅适用于 OPEN。
 */
public enum TaskStatus {

    /** 待处理（未开始）。 */
    OPEN,

    /** 进行中（已开始或已派工，未完成）。 */
    IN_PROGRESS,

    /** 被有效疏散区域阻断的未开始任务；区域结束或补发有效豁免后恢复 OPEN。 */
    EVACUATION_BLOCKED,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED）。 */
    DONE,

    /** 已取消。 */
    CANCELLED,

    /** 已撤离（进行中命中任务登记撤离）；终态，不可完成。 */
    EVACUATED;
}
