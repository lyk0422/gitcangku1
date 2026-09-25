package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * PENDING_INSPECTION 追缴回库待核验（须经封条核验通过回到 SEALED 后才可再次借出）；
 * SEAL_BROKEN 封条异常（终态，不可恢复）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    PENDING_INSPECTION,
    SEAL_BROKEN
}
