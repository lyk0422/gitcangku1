package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）。
 * TEMPERATURE_HOLD 为运输温控冻结的对外呈现状态：不写入 batch.status 列，
 * 存在未解除的温控冻结时由服务层叠加呈现，解除后恢复底层状态。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    TEMPERATURE_HOLD
}
