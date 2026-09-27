package com.example.starter.evidence;

/**
 * 跨案移交状态。
 * COMPLETED 已完成：一次提交在同一事务内完成移出/移入/快照，证物即刻归属目标案件；
 * REVOKED 已撤销（终态）：仅追加反向链，原移交与快照不删除、不可再变。
 */
public enum CaseTransferStatus {
    COMPLETED,
    REVOKED
}
