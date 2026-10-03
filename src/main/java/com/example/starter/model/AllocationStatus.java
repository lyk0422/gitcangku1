package com.example.starter.model;

/**
 * 接收时隙分配状态。
 */
public enum AllocationStatus {
    /** 已确认，占用天线时隙。 */
    CONFIRMED,
    /** 预测修订后不再满足新窗口，待重新分配，不占用天线。 */
    PENDING_REASSIGN,
    /** 接收完成，已记录实际字节数（终态）。 */
    COMPLETED,
    /** 已取消，释放天线时隙（终态）。 */
    CANCELLED
}