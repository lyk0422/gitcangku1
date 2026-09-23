package com.example.starter.incident;

/**
 * 联合交接单状态。
 * PENDING：已发起，等待指定接收人接受；
 * ACCEPTED：接收人已接受，闭包全部事件指挥人已在同一事务内切换，终态不可再变更。
 */
public enum HandoverStatus {

    /** 已发起，待接受。 */
    PENDING,

    /** 已接受，闭包快照已保存，终态。 */
    ACCEPTED
}
