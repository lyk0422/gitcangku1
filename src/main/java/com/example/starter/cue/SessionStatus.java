package com.example.starter.cue;

/** 排练场次状态。 */
public enum SessionStatus {
    /** 未开始，可重新绑定最新版本 */
    SCHEDULED,
    /** 进行中，提示点快照已冻结，按序回报执行 */
    RUNNING,
    /** 已结束，全部提示点执行完成 */
    FINISHED
}