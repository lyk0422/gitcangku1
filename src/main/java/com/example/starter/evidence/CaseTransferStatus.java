package com.example.starter.evidence;

/**
 * 跨案移交批次状态。
 * COMPLETED 已移交（即时完成，无中间态）；REVOKED 已撤销（原移交记录保留，仅追加反向链）。
 */
public enum CaseTransferStatus {
    COMPLETED,
    REVOKED
}
