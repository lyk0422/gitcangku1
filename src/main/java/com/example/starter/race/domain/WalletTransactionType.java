package com.example.starter.race.domain;

/**
 * 资金流水类型（金额单位均为人民币元、非负整数）：
 * TOPUP-充值，增加可用余额；
 * FREEZE-创建申诉冻结保证金并等额扣减可用余额；
 * REFUND-裁决 ACCEPT 全额退回，等额恢复可用余额；
 * FORFEIT-裁决 REJECT 全部没收，不再改变可用余额（冻结时已扣减）。
 */
public enum WalletTransactionType {
    TOPUP,
    FREEZE,
    REFUND,
    FORFEIT
}
