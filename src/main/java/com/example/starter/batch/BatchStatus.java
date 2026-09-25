package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）。
 * REJECTED 批次返工重投后原批次进入 REWORKED（终态，不可再放行、拆分或合批），
 * 同时生成代次加一的新返工批次；祖先召回闭包中已放行（RELEASED）的后代在同一事务内
 * 标记为 PENDING_DISPOSAL（待处置，终态：拦截放行/拆分，等待线下处置）。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    REWORKED,
    PENDING_DISPOSAL
}
