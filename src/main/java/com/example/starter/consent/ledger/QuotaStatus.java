package com.example.starter.consent.ledger;

/**
 * 用途额度状态：ACTIVE 已声明可核销；CLEARED 已清除（历史保留，新查询一律按用途未声明拒绝）。
 */
public enum QuotaStatus {
    ACTIVE,
    CLEARED
}
