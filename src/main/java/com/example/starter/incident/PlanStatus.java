package com.example.starter.incident;

/**
 * 指令计划状态机：RUNNING（未全部执行）→ COMPLETED（全部执行）；
 * RUNNING/COMPLETED 取消 → COMPENSATING（补偿中）→ CANCELLED（全部补偿完成）。
 * 无已执行步骤时取消直接进入 CANCELLED。RUNNING/COMPENSATING 阻止事件进入 RESOLVED。
 */
public enum PlanStatus {

    /** 执行中：尚有步骤未执行。 */
    RUNNING,

    /** 全部步骤已执行。 */
    COMPLETED,

    /** 补偿中：已取消，按已执行步骤逆序逐项补偿。 */
    COMPENSATING,

    /** 已取消：全部已执行步骤补偿完成（或无已执行步骤）。 */
    CANCELLED;
}
