package com.example.starter.evidence;

/**
 * 位置迁移单状态。
 * AWAITING_FIRST 待首人确认；AWAITING_SECOND 待第二人确认；
 * COMPLETED 双人确认完成（不可撤销、不可改写）；CANCELLED 已撤销（不可再确认、不可改写）。
 */
public enum MoveStatus {
    AWAITING_FIRST,
    AWAITING_SECOND,
    COMPLETED,
    CANCELLED
}
