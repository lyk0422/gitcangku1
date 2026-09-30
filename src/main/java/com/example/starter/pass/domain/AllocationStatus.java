package com.example.starter.pass.domain;

/**
 * 接收时隙分配（接收计划）的状态。
 *
 * <ul>
 *     <li>{@link #CONFIRMED}：已确认，正在占用对应天线的该段窗口；</li>
 *     <li>{@link #PENDING_REASSIGN}：预测修订后不再满足新窗口，已释放天线，等待重新分配；</li>
 *     <li>{@link #RECEIVED}：接收完成的终态，保留实际接收字节与确认时的预测版本证据；</li>
 *     <li>{@link #CANCELLED}：已取消的终态，记录明确取消原因并释放天线。</li>
 * </ul>
 */
public enum AllocationStatus {

    /** 已确认：天线时隙被占用。 */
    CONFIRMED,

    /** 待重新分配：预测修订后校核不通过，不再占用天线。 */
    PENDING_REASSIGN,

    /** 已接收：接收完成终态。 */
    RECEIVED,

    /** 已取消：取消终态。 */
    CANCELLED
}
