package com.example.starter.incident;

/**
 * 指令计划状态机：
 * RUNNING（执行中，含尚未执行任何步骤）→ COMPLETED（全部步骤 APPLIED）；
 * RUNNING 或 COMPLETED 可取消进入 COMPENSATING；COMPENSATING 逆序补偿完成后 → CANCELLED。
 * COMPENSATING/CANCELLED 为补偿阶段：COMPENSATING 阻止事件进入 RESOLVED，
 * 仅当全部计划 COMPLETED 或 CANCELLED 时事件才可解决。
 */
public enum PlanStatus {

    /** 执行中：至少还有步骤未 APPLIED（含一步未执行）。 */
    RUNNING,

    /** 全部步骤已 APPLIED。 */
    COMPLETED,

    /** 已取消，正在按已执行步骤逆序逐项补偿；未执行步骤不再执行。 */
    COMPENSATING,

    /** 全部已执行步骤补偿完成，终态。 */
    CANCELLED
}
