package com.example.starter.aging.domain;

/**
 * 测温记录状态：VALID 有效，VOID 已作废。作废仅影响尚未放行的资格审核。
 */
public enum TempStatus {
    VALID,
    VOID
}