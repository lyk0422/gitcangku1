package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）。
 * 召回处置确认后：DESTROY 分类批次进入 DESTROYED（销毁终态）；
 * REWORK 分类批次进入 REWORK_PENDING（返工待处理）；
 * HOLD 分类批次状态保持不变，仅记录暂挂原因。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    DESTROYED,
    REWORK_PENDING
}
