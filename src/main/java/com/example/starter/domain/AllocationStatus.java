package com.example.starter.domain;

/**
 * 靠泊申请状态。
 */
public enum AllocationStatus {

    /** 已确认，占用泊位区间。 */
    CONFIRMED,

    /** 潮高修订后深度不再满足，待人工复核；已释放泊位占用。 */
    PENDING_REVIEW,

    /** 已拒绝（深度不足、窗口冲突、复核驳回等），不占泊位。 */
    REJECTED
}
