package com.example.starter.evidence;

/**
 * 隔离记录状态。
 * QUARANTINED 隔离待复核；RELEASED 复核放行（历史记录保留，不再变更）。
 */
public enum IsolationStatus {
    QUARANTINED,
    RELEASED
}
