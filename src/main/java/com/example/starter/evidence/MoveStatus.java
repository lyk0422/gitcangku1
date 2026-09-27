package com.example.starter.evidence;

/**
 * 迁移单状态。
 * PENDING 待首人确认；FIRST_CONFIRMED 首人已确认、待第二人确认（可撤销）；
 * COMPLETED 双人确认完成（终态，不可撤销）；CANCELLED 已撤销（终态，不可再确认）。
 */
public enum MoveStatus {
    PENDING,
    FIRST_CONFIRMED,
    COMPLETED,
    CANCELLED
}
