package com.example.starter.evidence;

/**
 * 借出记录状态。
 * ACTIVE 未归还（每件证物至多一笔）；RETURNED 已归还（不可再变，历史不可覆盖）；
 * RECLAIMED 已追缴（终态，由保管人追缴流转，不可再归还）。
 * OVERDUE 为派生状态：数据库中仍存 ACTIVE，当可注入时钟的当前 UTC 时刻不早于
 * 应还时刻且未归还时，对外视图实时呈现为 OVERDUE，不依赖后台任务改写。
 */
public enum LoanStatus {
    ACTIVE,
    OVERDUE,
    RETURNED,
    RECLAIMED
}
