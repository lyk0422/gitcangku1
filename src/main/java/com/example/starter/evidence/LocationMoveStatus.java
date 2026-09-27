package com.example.starter.evidence;

/**
 * 库位迁移单状态。
 * AWAITING_SECOND 首人已确认（申请已受理），待第二人确认，此阶段可撤销；
 * COMPLETED 第二人已确认，迁移完成（终态，不可撤销）；
 * CANCELLED 已撤销（终态，不可再确认）。
 */
public enum LocationMoveStatus {
    AWAITING_SECOND,
    COMPLETED,
    CANCELLED
}
