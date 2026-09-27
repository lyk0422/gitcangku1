package com.example.starter.incident;

/**
 * 决策提案生效时原子写入的任务变更类型。
 */
public enum DecisionAction {

    /** 生效时将任务置为 DONE。 */
    COMPLETE,

    /** 生效时将任务置为 CANCELLED。 */
    CANCEL;
}
