package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * PENDING_RELEASE 可凭条件放行进入 CONDITIONAL（条件期内可用，全部子项核销后转 RELEASED，到期未核销降级不可用）；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    CONDITIONAL,
    RELEASED,
    REJECTED,
    RECALLED
}
