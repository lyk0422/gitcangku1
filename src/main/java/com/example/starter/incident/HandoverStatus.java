package com.example.starter.incident;

/**
 * 联合指挥交接单状态：PENDING 预览冻结后待接收人接受，ACCEPTED 已接受并原子完成全部事件切换。
 */
public enum HandoverStatus {

    /** 待接受；期间闭包内任一事件、OPEN 任务、依赖或未确认升级变化都会使接受返回 409。 */
    PENDING,

    /** 已被指定接收人接受，全部事件指挥人在同一事务切换完毕，不可变闭包快照已落库。 */
    ACCEPTED
}
