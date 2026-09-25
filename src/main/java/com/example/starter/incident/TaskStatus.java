package com.example.starter.incident;

/**
 * 处置任务状态机：
 * OPEN → IN_PROGRESS（开始，要求阻塞解除、已配租约且无资质风险），
 * IN_PROGRESS → DONE（完成，要求阻塞解除且无资质风险），
 * OPEN → CANCELLED（取消）。
 * 已开始任务的当前租约资质被提前撤销时 → CREDENTIAL_RISK（门禁，不得开始或完成），
 * 以合格资源替换租约后回到 IN_PROGRESS。
 * DONE 与 CANCELLED 均为终态；资质撤销不影响已完成任务。
 */
public enum TaskStatus {

    /** 待开始。 */
    OPEN,

    /** 进行中（已开始且当前租约资质有效）。 */
    IN_PROGRESS,

    /** 已完成（终态；要求全部阻塞事件已进入 CONTAINED/RESOLVED/CLOSED 且无资质风险）。 */
    DONE,

    /** 已取消（终态；取消仅适用于 OPEN）。 */
    CANCELLED,

    /** 资质风险门禁：当前租约资源的必需资质被提前撤销；不得开始或完成，直到替换为合格租约。 */
    CREDENTIAL_RISK;
}
