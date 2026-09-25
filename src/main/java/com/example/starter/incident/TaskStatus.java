package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → IN_PROGRESS → DONE；OPEN 可直接完成；
 * OPEN/IN_PROGRESS → CANCELLED；OPEN/IN_PROGRESS 可因必需资质被提前撤销
 * 进入 CREDENTIAL_RISK，以合格资源替换租约后回到风险前状态。
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转，资质撤销不改写终态任务。
 */
public enum TaskStatus {

    /** 待处理。 */
    OPEN,

    /** 进行中（已开始未完成）。 */
    IN_PROGRESS,

    /**
     * 资质风险：持有未来有效高危租约的资源资质被提前撤销后进入；
     * 该状态下不得开始或完成，直到以合格资源替换租约。
     */
    CREDENTIAL_RISK,

    /** 已完成（要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED，且不在资质风险中）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
