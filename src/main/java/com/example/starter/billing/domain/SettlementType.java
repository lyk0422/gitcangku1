package com.example.starter.billing.domain;

/**
 * 账目类型：首次结算或读数更正产生的差额结算。
 */
public enum SettlementType {

    /** 首次结算，按当时已提交的基线、实际读数版本计算。 */
    INITIAL,

    /** 更正结算，只记录新旧结果之间的差额（可为负），不覆盖首次结算。 */
    ADJUSTMENT
}
