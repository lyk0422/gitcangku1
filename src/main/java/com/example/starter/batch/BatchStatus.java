package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离（拆分产生的子批同样从此状态开始）；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次拆分后进入 SPLIT（不再可用，仅保留追溯关系）；
 * RELEASED 或 SPLIT 批次召回后进入 RECALLED（终态），其全部后代立即不可用但自身状态不改写。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    SPLIT,
    RECALLED
}
