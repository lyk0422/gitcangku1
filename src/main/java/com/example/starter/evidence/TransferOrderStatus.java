package com.example.starter.evidence;

/**
 * 移交令状态。
 * ACTIVE 有效；REVOKED 已撤销（终态，不可恢复）。
 */
public enum TransferOrderStatus {
    ACTIVE,
    REVOKED
}
