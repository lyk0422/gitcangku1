package com.example.starter.blind;

/**
 * 揭盲申请状态。
 */
public enum UnblindStatus {
    /** 待审（未到期）。 */
    PENDING,
    /** 已由另一名 REVIEWER 批准。 */
    APPROVED,
    /** 申请人（COORDINATOR）主动撤销。 */
    CANCELLED,
    /** 由另一名 REVIEWER 填写原因拒绝。 */
    REJECTED,
    /** 到达 expiresAt 仍未裁决；普通查询按时钟展示，不写库，新建申请时可归档占位。 */
    EXPIRED
}
