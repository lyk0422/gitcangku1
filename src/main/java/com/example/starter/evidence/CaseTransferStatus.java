package com.example.starter.evidence;

/**
 * 跨案移交状态。
 * COMPLETED 已完成；REVOKED 已撤销（终态，原移交记录不删除，仅追加反向链）。
 */
public enum CaseTransferStatus {
    COMPLETED,
    REVOKED
}
