package com.example.starter.incident;

/**
 * 交接结束触发原因（同时作为结算原因写入不可变交接结算）。
 */
public enum SettlementReason {

    /** 目标事件关闭触发归还。 */
    TARGET_CLOSED,

    /** 租约到期触发归还。 */
    LEASE_EXPIRED;
}
