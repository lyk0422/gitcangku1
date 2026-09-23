package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * SEAL_BROKEN 封条异常（仅可经双人重新封存确认解除，恢复 SEALED 并换用新封条）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    SEAL_BROKEN
}
