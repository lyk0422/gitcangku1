package com.example.starter.incident;

/**
 * 处置任务状态机：OPEN → STARTED → DONE/CANCELLED，或 OPEN → CANCELLED。
 * OPEN → STARTED 必须持有覆盖所需共享资源的 ACTIVE 租约；
 * DONE 与 CANCELLED 均为终态，不允许任何后续流转。
 */
public enum TaskStatus {

    /** 待处理，尚未开始；可申请/持有 ACTIVE 租约，但未进入执行。 */
    OPEN,

    /** 已开始：持有有效 ACTIVE 租约后才可进入，进行中不可被抢占。 */
    STARTED,

    /** 已完成（STARTED 后完成；完成时原子释放全部 ACTIVE 租约）。 */
    DONE,

    /** 已取消（OPEN 或 STARTED 均可取消；取消时原子释放全部 ACTIVE 租约）。 */
    CANCELLED;
}
