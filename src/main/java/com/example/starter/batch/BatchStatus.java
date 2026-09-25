package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）。
 * PENDING_DISPOSITION 待处置：已放行批次出现未裁决 MAJOR 储运偏差后立即转入，
 * 历史放行记录保留；REWORKED 返工终态：MAJOR 偏差裁决为 REWORK 后原批次进入，
 * 处置沿返工链落到新返工批。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    PENDING_DISPOSITION,
    REWORKED
}
