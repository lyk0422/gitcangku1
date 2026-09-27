package com.example.starter.evidence;

/**
 * 跨案移交明细状态。
 * TRANSFERRED 已移交；REVOKED 已撤销（原明细保留，追加反向链）。
 */
public enum CaseTransferItemStatus {
    TRANSFERRED,
    REVOKED
}
