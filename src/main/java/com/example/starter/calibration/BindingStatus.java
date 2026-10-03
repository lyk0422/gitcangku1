package com.example.starter.calibration;

/**
 * 任务绑定状态。
 */
public enum BindingStatus {
    /** 已绑定，占用天线对应时段。 */
    BOUND,
    /** 依据的校准被作废且任务未开始，等待重新分配校准。 */
    PENDING_REASSIGN,
    /** 待重配后已被新的绑定替换。 */
    SUPERSEDED
}