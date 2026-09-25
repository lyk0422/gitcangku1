package com.example.starter.evidence;

/**
 * 借出记录状态。
 * ACTIVE 未归还（每件证物至多一笔）；RETURNED 已归还（不可再变，历史不可覆盖）；
 * RECLAIMED 已追缴（终态，同一借出记录只能被追缴一次）。
 * OVERDUE 为派生状态：ACTIVE 且当前 UTC 时刻不早于应还时刻时实时判定，不落库、不依赖后台任务。
 */
public enum LoanStatus {
    ACTIVE,
    OVERDUE,
    RETURNED,
    RECLAIMED
}
