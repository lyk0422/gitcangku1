package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * SEAL_BROKEN 封条异常（终态，不可恢复）；
 * DESTROYED 已销毁（终态，保管链封存，原保管链/借出/封条记录原样保留可查，禁止任何写操作）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    SEAL_BROKEN,
    DESTROYED
}
