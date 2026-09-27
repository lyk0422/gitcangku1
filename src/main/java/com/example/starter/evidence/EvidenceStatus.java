package com.example.starter.evidence;

/**
 * 证物状态。
 * SEALED 已封存（在库）；TRANSFER_PENDING 待接收（交接进行中）；BORROWED 借出未归还；
 * EXAMINATION 检验中（已离开库房进入检验室，完成并通过新封签校验后回到 SEALED）；
 * SEAL_BROKEN 封条异常（终态，不可恢复）。
 */
public enum EvidenceStatus {
    SEALED,
    TRANSFER_PENDING,
    BORROWED,
    EXAMINATION,
    SEAL_BROKEN
}
