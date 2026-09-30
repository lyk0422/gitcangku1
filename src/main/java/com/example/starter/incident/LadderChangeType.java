package com.example.starter.incident;

/**
 * 升级阶梯等级变更类型。
 * PROMOTE 为分数达到更高阈值时的自动升级；DEMOTE 为满足确认人与冷却条件的人工降级。
 */
public enum LadderChangeType {

    /** 自动升级（可跨多级，只升不降）。 */
    PROMOTE,

    /** 人工降级（必须逐级，由不同于当前指挥人的指挥员确认）。 */
    DEMOTE
}
