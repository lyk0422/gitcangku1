package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * SEAL_BROKEN 封条异常（终态，不可恢复）；DESTROYED 已完成销毁（终态，保管链与冻结快照不得改写）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    SEAL_BROKEN,
    DESTROYED
}
