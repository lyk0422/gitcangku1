package com.example.starter.curtailment.domain;

/**
 * 限发指令状态。PENDING/CONFIRMED/REVOKED 落库；ENDED 为派生状态（已确认且当前时间越过区间终点），
 * 不单独落库，已结束指令不因迟到更正恢复为运行中。
 */
public enum InstructionStatus {
    /** 待确认：指令已创建，机组尚未在区间开始前确认。 */
    PENDING,
    /** 已确认：机组已在区间开始前确认，指令生效。 */
    CONFIRMED,
    /** 已撤销：指令被调度撤销，不再参与上报与对账。 */
    REVOKED,
    /** 已结束（派生）：已确认且当前 UTC 时间不小于区间终点。 */
    ENDED
}