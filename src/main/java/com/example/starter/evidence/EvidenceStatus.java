package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * SEAL_BROKEN 封条异常（终态，不可恢复）；
 * ALIAS 已合并为别名（终态）：可查询并指向最终主号，所有写入口一律拒绝且不自动重定向。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    SEAL_BROKEN,
    ALIAS
}
