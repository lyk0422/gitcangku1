package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）。
 * RELEASE_HOLD：已放行（RELEASED）批次在放行后产生未处置仓储温度偏差时进入的挂起态，
 * 门禁关闭、禁止拆分与合批；全部偏差经两名不同角色确认解除后恢复 RELEASED。
 * 未放行批次存在未处置偏差时不新增状态，由偏差表门禁阻止批准完成放行。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    RELEASE_HOLD
}
