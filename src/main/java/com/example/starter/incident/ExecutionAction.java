package com.example.starter.incident;

/**
 * 任务执行动作，对应 incident_task_executions.action。
 * START 每任务至多一次；CONTINUE 须已 START；两者均要求授权未到期。
 */
public enum ExecutionAction {

    /** 开始执行。 */
    START,

    /** 继续执行。 */
    CONTINUE;
}
