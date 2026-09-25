package com.example.starter.domain;

/** 抢占记录处理状态。 */
public enum PreemptionState {
    /** 未处理：被置换航线尚未重新提交审查；同一航线同一时间只允许一条。 */
    PENDING,
    /** 已处理：被置换航线已重新提交审查。 */
    RESUBMITTED
}
