package com.example.starter.incident;

/**
 * 计划步骤状态机：PENDING → APPLIED → COMPENSATED。
 * PENDING 未执行；APPLIED 已生效（原子记录一次，重复执行旧成功步骤不重复记效果）；
 * COMPENSATED 已补偿。进入 COMPENSATING 后 PENDING 步骤永久不再执行。
 */
public enum PlanStepStatus {

    /** 未执行。 */
    PENDING,

    /** 已生效：显式执行当前下一步时原子置为该状态。 */
    APPLIED,

    /** 已补偿：补偿成功后由 APPLIED 转入，终态。 */
    COMPENSATED
}
