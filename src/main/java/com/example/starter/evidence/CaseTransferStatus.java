package com.example.starter.evidence;

/**
 * 跨案移交状态。
 * COMPLETED 已移交（目标案件承接后续借出与归还）；REVOKED 已撤销（追加反向链，原移交记录保留）。
 */
public enum CaseTransferStatus {
    COMPLETED,
    REVOKED
}
