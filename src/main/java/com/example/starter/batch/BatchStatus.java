package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）；
 * RELEASED 批次合批后进入 MERGED（终态，库存并入目标批，仍可被召回以隔离其后代）；
 * 已放行批次成分修订发现新增过敏原时进入 ALLERGEN_RISK（保留原放行快照，
 * 仅可由重新检验加双角色放行解除回到 RELEASED）。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    MERGED,
    ALLERGEN_RISK
}
