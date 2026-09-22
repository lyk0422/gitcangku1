package com.example.starter.evidence;

/**
 * 借出记录状态。
 * ACTIVE 未归还（每件证物至多一笔）；RETURNED 已归还（不可再变，历史不可覆盖）。
 */
public enum LoanStatus {
    ACTIVE,
    RETURNED
}
