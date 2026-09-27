package com.example.starter.incident;

/**
 * 提案生效时原子写入的任务变更类型：完成或取消处置任务。
 */
public enum DecisionType {

    /** 生效时将任务 OPEN → DONE（仍须满足全部阻塞事件已解除的门禁）。 */
    TASK_COMPLETE,

    /** 生效时将任务 OPEN → CANCELLED。 */
    TASK_CANCEL;
}
