package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * SEAL_BROKEN 封条异常（终态，不可恢复）；
 * CONTAMINATED 已污染隔离（终态，不可解除），与封条异常分别保存，不伪造 SEAL_BROKEN。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    SEAL_BROKEN,
    CONTAMINATED
}
