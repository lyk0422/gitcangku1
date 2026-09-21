package com.example.starter.evidence.domain;

/**
 * 证物状态：SEALED 已封存；TRANSFER_PENDING 交接待接收；SEAL_BROKEN 封条异常（终态，不可恢复）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    SEAL_BROKEN
}
