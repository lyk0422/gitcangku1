package com.example.starter.evidence;

/**
 * 检验预约状态。状态机只允许：
 * RESERVED -> IN_PROGRESS -> COMPLETED，或 RESERVED -> CANCELLED。
 * RESERVED/IN_PROGRESS 占用检验台与证物；COMPLETED/CANCELLED 释放占用，且不可再变。
 */
public enum ExamReservationStatus {
    RESERVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
