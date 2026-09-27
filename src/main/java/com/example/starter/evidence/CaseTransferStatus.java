package com.example.starter.evidence;

/**
 * 跨案移交单状态。
 * TRANSFERRED 已移交（来源案只读留存，目标案承接后续操作）；
 * REVOKED 已撤销（追加反向链，不删除原移交与快照）。
 */
public enum CaseTransferStatus {
    TRANSFERRED,
    REVOKED
}
