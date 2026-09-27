package com.example.starter.evidence;

/**
 * 检验预约状态。
 * RESERVED 已预约未开始（可取消）；IN_PROGRESS 检验中（不可取消）；
 * COMPLETED 已完成（终态）；CANCELLED 已取消（终态）。
 */
public enum ExamReservationStatus {
    RESERVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
