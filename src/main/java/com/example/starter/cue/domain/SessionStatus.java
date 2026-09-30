package com.example.starter.cue.domain;

/**
 * 排练场次状态。
 */
public enum SessionStatus {

    /** 未开始：可重新绑定最新编排版本，尚未冻结快照。 */
    NOT_STARTED,

    /** 运行中：已冻结提示点版本与顺序，操作员只可按顺序回报。 */
    RUNNING,

    /** 已结束：全部提示点成功执行完成，不再接受回报。 */
    FINISHED
}
