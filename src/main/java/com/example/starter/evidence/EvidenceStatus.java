package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存（在库且封签正常）；TRANSFER_PENDING 待接收（交接进行中）；
 * BORROWED 借出未归还；EXAMINATION 检验中（检验台占用、原封签版本已锁定）；
 * SEAL_BROKEN 封条异常（终态，不可恢复）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    EXAMINATION,
    SEAL_BROKEN
}
