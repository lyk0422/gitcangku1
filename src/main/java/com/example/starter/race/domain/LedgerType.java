package com.example.starter.race.domain;

/**
 * 保证金账务流水类型：
 * RECHARGE-充值入账；FREEZE-创建申诉时冻结；
 * REFUND-裁决 ACCEPT 全额退回；FORFEIT-裁决 REJECT 全部没收。
 */
public enum LedgerType {
    RECHARGE,
    FREEZE,
    REFUND,
    FORFEIT
}
