package com.example.starter.evidence;

/**
 * 借出记录状态。
 * ACTIVE 未归还（每件证物同时至多一笔）；RETURNED 已归还（记录不可再变）。
 */
public enum LoanStatus {
    ACTIVE,
    RETURNED
}
