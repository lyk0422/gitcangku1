package com.example.starter.batch;

/**
 * 批次状态机。
 * QUARANTINED 初始隔离；全部必做检验项 PASS 后进入 PENDING_RELEASE；
 * 首个有效批准进入 RELEASE_REVIEW；第二个不同角色批准进入 RELEASED；
 * 任一 FAIL 立即进入 REJECTED（终态）；已放行批次召回后进入 RECALLED（终态）；
 * RELEASED 批次拆分后进入 SPLIT（终态，父批不再可用，可整体召回）；
 * IN_TRANSIT 为跨厂移交发运后的在途状态：仅允许目标厂整体接收或源厂整单取消，
 * 接收成功后批次在目标厂以 QUARANTINED 落定，取消则恢复发运前状态。
 */
public enum BatchStatus {
    QUARANTINED,
    PENDING_RELEASE,
    RELEASE_REVIEW,
    RELEASED,
    REJECTED,
    RECALLED,
    SPLIT,
    IN_TRANSIT
}
