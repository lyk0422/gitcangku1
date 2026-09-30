package com.example.starter.incident;

/**
 * 升级阶梯等级变更类型：ESCALATE 升级（分数达阈值自动提升，可跨级）；
 * DEESCALATE 降级（逐级，需不同指挥员确认且冷却已过）。
 */
public enum LadderChangeType {

    /** 升级：分数达到更高阈值时自动提升，允许跨级。 */
    ESCALATE,

    /** 降级：必须由不同指挥员确认，逐级下降且冷却已过。 */
    DEESCALATE;
}
