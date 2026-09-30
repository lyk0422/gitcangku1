package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * SEAL_BROKEN 封条异常（终态，不可恢复）；
 * QUARANTINED 隔离待复核（异常容器双人巡检确认后进入，复核放行回到 SEALED）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    SEAL_BROKEN,
    QUARANTINED
}
