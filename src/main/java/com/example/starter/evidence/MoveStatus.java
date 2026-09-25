package com.example.starter.evidence;

/**
 * 迁移单状态机：PENDING 待确认 → FIRST_CONFIRMED 首人已确认 → COMPLETED 已完成（终态）；
 * FIRST_CONFIRMED 可撤销为 CANCELLED（终态），撤销后不可再确认，完成后不可撤销。
 */
public enum MoveStatus {
    PENDING,
    FIRST_CONFIRMED,
    COMPLETED,
    CANCELLED
}
