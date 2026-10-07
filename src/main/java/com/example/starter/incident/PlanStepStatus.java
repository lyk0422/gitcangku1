package com.example.starter.incident;

/**
 * 指令计划步骤状态机：PENDING（未执行）→ APPLIED（已执行）→ COMPENSATED（已补偿）。
 * 执行按顺序只允许当前下一步；补偿按已执行步骤逆序逐项进行。
 */
public enum PlanStepStatus {

    /** 未执行。 */
    PENDING,

    /** 已执行（合成效果已记录）。 */
    APPLIED,

    /** 已补偿。 */
    COMPENSATED;
}
