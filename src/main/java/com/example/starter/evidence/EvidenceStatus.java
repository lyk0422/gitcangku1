package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * PENDING_VERIFICATION 容器巡检 FAIL 后待双人复核（期间禁止新借出与迁移）；
 * SEAL_BROKEN 封条异常（终态，不可恢复）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    PENDING_VERIFICATION,
    SEAL_BROKEN
}
