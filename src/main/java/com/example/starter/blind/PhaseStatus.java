package com.example.starter.blind;

/**
 * 交叉实验阶段状态。
 */
public enum PhaseStatus {
    /** 尚未开始第一阶段（登记后的初始状态）。 */
    NOT_STARTED,
    /** 第一阶段进行中。 */
    FIRST_RUNNING,
    /** 第一阶段已结束，洗脱中。 */
    WASHOUT,
    /** 第二阶段进行中。 */
    SECOND_RUNNING,
    /** 两阶段均已完成。 */
    DONE
}
