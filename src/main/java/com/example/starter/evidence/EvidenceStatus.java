package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * QUARANTINED 容器封条异常双人确认后的隔离待复核状态（复核放行后回到 SEALED）；
 * SEAL_BROKEN 证物自身封条异常（终态，不可恢复）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    QUARANTINED,
    SEAL_BROKEN
}
