package com.example.starter.consent.ledger;

/**
 * 账本条目类型：QUOTA_ADJUST 额度调整；QUERY 受限查询核销；CLEAR 额度清除。
 */
public enum LedgerEntryType {
    QUOTA_ADJUST,
    QUERY,
    CLEAR
}
