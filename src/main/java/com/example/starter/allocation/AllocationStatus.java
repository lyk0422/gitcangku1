package com.example.starter.allocation;

/**
 * 时隙分配状态机：CONFIRMED 可转为 COMPLETED、CANCELLED 或 PENDING_REASSIGNMENT；
 * PENDING_REASSIGNMENT 可被取消；COMPLETED 与 CANCELLED 为终态。
 */
public enum AllocationStatus {
    /** 已确认，占用天线。 */
    CONFIRMED,
    /** 已完成接收，记录实际字节数，保留旧预测证据。 */
    COMPLETED,
    /** 已取消，释放天线。 */
    CANCELLED,
    /** 预测修订后校核不通过，待重新分配，不占用天线。 */
    PENDING_REASSIGNMENT
}